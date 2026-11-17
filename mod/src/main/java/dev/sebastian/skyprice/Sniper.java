package dev.sebastian.skyprice;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import net.minecraft.client.Minecraft;

/**
 * Undervalued-BIN alert: a banner at the top of the screen (plus a ping and a chat line) as soon as a BIN that's clearly
 * under the market appears on the AH.
 *
 * How fast: Hypixel publishes the auction list about once a minute, newest first. The mod watches for that update
 * (polling page 0 every 3 s only around the time it's due) and checks just the new listings, so an alert shows a few
 * seconds after Hypixel publishes it — within about a minute of the seller listing it, the API's limit.
 *
 * What counts as undervalued (same rules as the AH market flips, see FlipFeed):
 *   - compared only with IDENTICAL copies (item + stars, recomb, enchants…) from the last full AH scan,
 *     with at least 3 of them listed;
 *   - at least snipeMinPercent (default 50 %) under the lowest BIN of those copies, i.e. at most half of it;
 *   - resale = just under that copy, never above fair value or what the cheapest copies really sold for lately;
 *   - profit after AH fees ≥ minFlipProfitCoins and ≥ 5 %; no manipulation flag; fits your purse.
 * Pets and enchanted books are skipped.
 * With snipeAutoOpen the auction view opens by itself (/viewauction, at most once per 10 s, not while chat or a sign is open, so it
 * never interrupts typing). Nothing is bought for you: you press Buy Item Right Now and Confirm yourself.
 */
final class Sniper {
	record Alert(String uuid, String name, String key, long count, double price, double resale, double profit, double pct,
			boolean checked, long at) {}

	/** bucket (item#variant) → {cheapest, reference = min(2nd, median 2nd–5th), listed} from the last full scan. */
	private static volatile Map<String, double[]> refs = Map.of();
	private static final List<Alert> alerts = new ArrayList<>();
	private static final Set<String> seen = new LinkedHashSet<>();
	private static SkyPriceConfig cfg;
	private static volatile long lastUpdated;
	private static Thread thread;
	static final long SHOW_MS = 12_000;

	private Sniper() {}

	static void init(SkyPriceConfig config) {
		cfg = config;
		HudHook.register("snipe_banner", (p, size) -> {
			if (BidHelper.currentScreen() == null) paint(p, size[0]);
		});
		thread = new Thread(Sniper::loop, "SkyPrice-sniper");
		thread.setDaemon(true);
		thread.start();
	}

	/** Called by FlipFeed at the end of a full scan. */
	static void setRefs(Map<String, double[]> r) {
		refs = r;
	}

	static synchronized Alert latest() {
		return alerts.isEmpty() ? null : alerts.get(alerts.size() - 1);
	}

	static synchronized List<Alert> recent() {
		return new ArrayList<>(alerts);
	}

	// ------------------------------------------------------------------ watching
	private static void loop() {
		while (true) {
			try {
				Thread.sleep(3000);
				if (cfg == null || !cfg.sniper || Minecraft.getInstance().player == null) continue;
				if (refs.isEmpty()) {                                   // needs one full scan for the reference prices
					AhScanner.poke(cfg, false);
					continue;
				}
				long now = System.currentTimeMillis();
				if (lastUpdated > 0 && now < lastUpdated + 55_000 && now > lastUpdated) continue;   // next update not due yet
				poll();
			} catch (InterruptedException e) {
				return;
			} catch (Exception ignored) {
				// network hiccup: try again next round
			}
		}
	}

	private static void poll() throws Exception {
		String me = Minecraft.getInstance().player.getUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
		long prev = lastUpdated;
		for (int p = 0; p < 4; p++) {
			long updated = 0;
			boolean anyNew = false;
			List<JsonObject> fresh = new ArrayList<>();
			try (JsonReader r = new JsonReader(new InputStreamReader(AhScanner.page(p), StandardCharsets.UTF_8))) {
				r.beginObject();
				while (r.hasNext()) {
					String name = r.nextName();
					if (name.equals("lastUpdated")) updated = r.nextLong();
					else if (name.equals("auctions")) {
						r.beginArray();
						while (r.hasNext()) fresh.add(JsonParser.parseReader(r).getAsJsonObject());
						r.endArray();
					} else r.skipValue();
				}
				r.endObject();
			}
			if (p == 0) {
				if (updated <= prev) return;                          // nothing new published yet
				lastUpdated = updated;
				if (prev == 0) prev = updated - 70_000;               // first look: the last minute or so
			}
			for (JsonObject a : fresh) {
				long start = a.has("start") ? a.get("start").getAsLong() : 0;
				if (start < prev - 5_000) continue;
				anyNew = true;
				if (me.equals(a.has("auctioneer") ? a.get("auctioneer").getAsString().replace("-", "") : "")) continue;
				Alert al = judge(a, cfg);
				if (al != null) fire(al);
			}
			if (!anyNew) return;                                       // newest first: older pages have nothing new
		}
	}

	/** Undervalued? (see class doc) Null when not. */
	static Alert judge(JsonObject a, SkyPriceConfig cfg) {
		if (!a.has("bin") || !a.get("bin").getAsBoolean() || a.has("claimed") && a.get("claimed").getAsBoolean()) return null;
		if (!a.has("item_bytes") || !a.has("starting_bid") || !a.has("uuid")) return null;
		String uuid = a.get("uuid").getAsString();
		synchronized (Sniper.class) {
			if (seen.contains(uuid)) return null;
			seen.add(uuid);
			if (seen.size() > 5000) seen.remove(seen.iterator().next());
		}
		String[] key = {null};
		int[] count = {1};
		String[] variant = {""};
		try {
			AhScanner.identify(a.get("item_bytes").getAsString(), key, count, variant);
		} catch (Exception e) {
			return null;
		}
		String k = key[0];
		if (k == null || k.startsWith("PET") || k.equals("ENCHANTED_BOOK")) return null;
		String bucket = variant[0] == null || variant[0].isEmpty() ? k : k + "#" + variant[0];
		double[] r = refs.get(bucket);
		if (r == null || r[2] < 3) return null;
		long n = Math.max(1, count[0]);
		double total = a.get("starting_bid").getAsDouble(), unit = total / n;
		double ref = Math.min(r[0], r[1]);
		boolean checked;
		double resale = Market.undercut(ref);
		JsonObject d = SkyPriceClient.docFor(k, false);
		List<double[]> week = SkyPriceClient.cofl == null ? null : SkyPriceClient.cofl.week(k, false);
		if (d != null && Market.hasSevereFlag(d)) return null;
		Double fair = d == null ? null : Verdict.fair(d);
		double floor = week == null ? 0 : FlipFeed.clearingFloor(week);
		if (fair != null && fair > 0) resale = Math.min(resale, fair);
		if (floor > 0) resale = Math.min(resale, Market.undercut(floor));
		checked = fair != null || floor > 0;
		double need = Math.max(0, Math.min(0.95, cfg.snipeMinPercent / 100.0));
		if (unit > r[0] * (1 - need)) return null;                    // e.g. 50 % → at most half the lowest BIN
		double profit = (Verdict.netAfterFees(resale * n) - total);
		double pct = profit / total;
		if (profit < cfg.minFlipProfitCoins || pct < (checked ? 0.05 : 0.10)) return null;
		Double purse = FcfPanel.purse();
		if (purse != null && total > purse) return null;
		synchronized (Sniper.class) {                                  // the new copy is now the cheapest one
			Map<String, double[]> m = new HashMap<>(refs);
			m.put(bucket, new double[]{unit, ref, r[2] + 1});
			refs = m;
		}
		String name = a.has("item_name") ? a.get("item_name").getAsString().replaceAll("§.", "") : k;
		return new Alert(uuid, name, k, n, total, resale * n, profit, pct, checked, System.currentTimeMillis());
	}

	private static volatile long autoOpenedAt;

	private static void fire(Alert al) {
		synchronized (Sniper.class) {
			alerts.add(al);
			if (alerts.size() > 20) alerts.remove(0);
		}
		Minecraft.getInstance().execute(() -> {
			Chat.ping();
			long now = System.currentTimeMillis();
			var scr = BidHelper.currentScreen();                          // never while typing (chat, signs); menus are fine
			boolean free = scr == null || scr instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
			if (cfg.snipeAutoOpen && free && now - autoOpenedAt > 10_000L) {
				autoOpenedAt = now;
				Chat.command("viewauction " + al.uuid());                // the BIN view pops up; buying stays your click
			}
			Chat.say(HudFormatter.line("[SkyPrice] ⚡ UNDERVALUED BIN ", HudFormatter.GOLD, al.name() + " " + HudFormatter.fmt(al.price()), HudFormatter.WHITE,
					String.format(Locale.ROOT, " → resell ≈ %s, +%s (%.0f%%) after fees", HudFormatter.fmt(al.resale()), HudFormatter.fmt(al.profit()), al.pct() * 100),
					HudFormatter.GREEN, "  /skyprice snipe opens it", HudFormatter.GRAY));
		});
	}

	static void openLatest() {
		Alert a = latest();
		if (a != null) Chat.command("viewauction " + a.uuid());
	}

	// ------------------------------------------------------------------ banner
	/** Top-centre banner while the newest alert is younger than 12 s. Also drawn over menus (click it to open). */
	static void paint(Highlight.Painter p, int screenW) {
		Alert a = latest();
		if (a == null || System.currentTimeMillis() - a.at() > SHOW_MS) return;
		Minecraft mc = Minecraft.getInstance();
		String l1 = "⚡ UNDERVALUED BIN  " + a.name() + (a.count() > 1 ? " ×" + a.count() : "");
		String l2 = String.format(Locale.ROOT, "%s → resell ≈ %s   +%s (%.0f%%) after fees%s", HudFormatter.fmt(a.price()),
				HudFormatter.fmt(a.resale()), HudFormatter.fmt(a.profit()), a.pct() * 100, a.checked() ? "" : "  (no sales history yet)");
		String l3 = BidHelper.currentScreen() == null ? "Type /skyprice snipe to open it (or open any menu and click this)"
				: cfg.snipeAutoOpen && System.currentTimeMillis() - autoOpenedAt < SHOW_MS ? "Opened: press Buy Item Right Now, then Confirm"
				: "Click here to open it";
		int w = Math.max(mc.font.width(l1), Math.max(mc.font.width(l2), mc.font.width(l3))) + 16;
		int x = (screenW - w) / 2, y = 4;
		long age = System.currentTimeMillis() - a.at();
		int border = (age / 400) % 2 == 0 ? 0xFFFFAA00 : 0xFFFFD35C;          // blinks gently
		p.fill(x - 1, y - 1, x + w + 1, y + 37, border);
		p.fill(x, y, x + w, y + 36, 0xF0101418);
		p.text(l1, x + 8, y + 3, 0xFFFFAA00);
		p.text(l2, x + 8, y + 14, 0xFF55FF55);
		p.text(l3, x + 8, y + 25, 0xFFAAAAAA);
		bannerBox = new int[]{x, y, x + w, y + 36};
		bannerAt = System.currentTimeMillis();
	}

	private static volatile int[] bannerBox;
	private static volatile long bannerAt;

	/** In a menu: a click on the banner opens the auction. True when it was used. */
	static boolean click(double mx, double my) {
		int[] b = bannerBox;
		if (b == null || System.currentTimeMillis() - bannerAt > 500) return false;
		if (mx < b[0] || mx > b[2] || my < b[1] || my > b[3]) return false;
		openLatest();
		return true;
	}
}
