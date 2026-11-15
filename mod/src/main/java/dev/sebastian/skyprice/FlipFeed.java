package dev.sebastian.skyprice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;

/**
 * AH flips found by the mod's own AH scan (every scanMinutes while you play, see AhScanner).
 *
 * While the scan reads every page, each BIN's item is identified (same key as the tooltip) and the cheapest 6 copies
 * of every item are kept. A candidate is an item whose cheapest BIN sits clearly below the next copies:
 *
 *   reference = min(2nd cheapest, median of the 2nd–5th cheapest)     (needs ≥ 3 listed)
 *   resale    = just under the reference, never above the item's fair value (tooltip data / SkyCofl median)
 *   profit    = resale after AH fees − price
 *
 * It's shown only if: profit ≥ your minimum (% and coins), the item sells ≥ minSalesPerDay, it should resell within
 * maxHoldHours, nothing flags manipulation, and the price fits your budget. Pets are skipped (level changes the price
 * too much to compare listings). Always check the auction's own tooltip before buying: it values that exact copy.
 */
final class FlipFeed {
	record Candidate(String key, String name, String uuid, double price, double ref, int listed, long scanAt) {}

	record Flip(Candidate c, double resale, double net, double profit, double pct, Market.Liquidity liq) {}

	record View(List<Flip> flips, int checking, int candidates) {}

	private static final int KEEP = 6;

	private static final class Bucket {
		final double[] p = new double[KEEP];
		final String[] u = new String[KEEP];
		final String[] names = new String[KEEP];
		int n, listed;

		void add(double price, String uuid, String name) {
			listed++;
			if (n == KEEP && price >= p[KEEP - 1]) return;
			int i = n < KEEP ? n++ : KEEP - 1;
			while (i > 0 && p[i - 1] > price) {
				p[i] = p[i - 1];
				u[i] = u[i - 1];
				names[i] = names[i - 1];
				i--;
			}
			p[i] = price;
			u[i] = uuid;
			names[i] = name;
		}
	}

	private static Map<String, Bucket> building = new HashMap<>();
	private static volatile List<Candidate> candidates = List.of();
	private static volatile long scanAt;
	private static final Set<String> announced = new HashSet<>();
	private static SkyPriceConfig cfg;
	private static long checkedAt;
	private static volatile List<Flip> lastValid = List.of();

	private FlipFeed() {}

	static void init(SkyPriceConfig config) {
		cfg = config;
	}

	static long scanAt() {
		return scanAt;
	}

	// ------------------------------------------------------------------ collecting (scan thread)
	static void begin() {
		building = new HashMap<>();
	}

	/** One auction from the scan (not yours). */
	static void collect(JsonObject a, long now) {
		if (!a.has("bin") || !a.get("bin").getAsBoolean()) return;
		if (a.has("claimed") && a.get("claimed").getAsBoolean()) return;
		if (a.has("end") && a.get("end").getAsLong() <= now) return;
		if (!a.has("item_bytes") || !a.has("starting_bid")) return;
		String[] key = {null};
		int[] count = {1};
		try {
			AhScanner.identify(a.get("item_bytes").getAsString(), key, count);
		} catch (Exception e) {
			return;
		}
		String k = key[0];
		if (k == null || k.startsWith("PET") || k.equals("ENCHANTED_BOOK")) return;
		double unit = a.get("starting_bid").getAsDouble() / Math.max(1, count[0]);
		if (unit <= 0) return;
		String name = a.has("item_name") ? a.get("item_name").getAsString() : k;
		building.computeIfAbsent(k, x -> new Bucket()).add(unit, a.get("uuid").getAsString(), name);
	}

	/** End of a scan: rank the gaps and start fetching prices for the best ones. */
	static void finish(long ts) {
		List<Candidate> out = new ArrayList<>();
		for (Map.Entry<String, Bucket> e : building.entrySet()) {
			Candidate c = candidate(e.getKey(), e.getValue(), ts);
			if (c != null) out.add(c);
		}
		out.sort((x, y) -> Double.compare(rawProfit(y), rawProfit(x)));
		if (out.size() > 80) out = new ArrayList<>(out.subList(0, 80));
		candidates = out;
		scanAt = ts;
		building = new HashMap<>();
		for (int i = 0; i < Math.min(40, out.size()); i++) SkyPriceClient.docFor(out.get(i).key(), true);   // warm prices (SkyCofl is rate limited)
	}

	private static Candidate candidate(String key, Bucket b, long ts) {
		if (b.n < 3) return null;
		List<Double> next = new ArrayList<>();
		for (int i = 1; i < Math.min(b.n, 5); i++) next.add(b.p[i]);
		double ref = Math.min(b.p[1], Market.median(next));
		if (b.p[0] >= ref * 0.97) return null;
		Candidate c = new Candidate(key, b.names[0], b.u[0], b.p[0], ref, b.listed, ts);
		double raw = rawProfit(c);
		return raw > 0 && raw / c.price() >= 0.02 ? c : null;
	}

	static double rawProfit(Candidate c) {
		return Verdict.netAfterFees(Market.undercut(c.ref())) - c.price();
	}

	// ------------------------------------------------------------------ what you see
	/** Valid flips for your settings and budget, best profit first. */
	static View view(double budget) {
		List<Flip> out = new ArrayList<>();
		int checking = 0;
		double minPct = cfg.minFlipProfitPercent / 100.0;
		for (Candidate c : candidates) {
			if (c.price() > budget) continue;
			JsonObject d = SkyPriceClient.docFor(c.key(), false);
			if (d == null) {
				checking++;
				continue;
			}
			Flip f = judge(c, d);
			if (f == null) continue;
			if (f.pct() < minPct || f.profit() < cfg.minFlipProfitCoins) continue;
			if (f.liq().perDay() < cfg.minSalesPerDay || f.liq().etaHours() > cfg.maxHoldHours) continue;
			if (Market.hasSevereFlag(d)) continue;
			out.add(f);
		}
		out.sort((x, y) -> Double.compare(y.profit(), x.profit()));
		lastValid = out;
		return new View(out, checking, candidates.size());
	}

	/** Pure: resale capped at fair value, profit after fees, liquidity. Null when fair value is unknown. */
	static Flip judge(Candidate c, JsonObject d) {
		Double fair = Verdict.fair(d);
		if (fair == null || fair <= 0) return null;
		double resale = Math.min(Market.undercut(c.ref()), fair);
		double net = Verdict.netAfterFees(resale);
		double profit = net - c.price();
		return new Flip(c, resale, net, profit, profit / c.price(), Market.liquidity(d, fair));
	}

	static List<Flip> last() {
		return lastValid;
	}

	/** Every ~10 s: announce the best new flips (max 3 per scan). */
	static void tick(double budget) {
		if (cfg == null || !cfg.flipFeed || !cfg.flipAlerts || candidates.isEmpty()) return;
		long now = System.currentTimeMillis();
		if (now - checkedAt < 10_000) return;
		checkedAt = now;
		View v = view(budget);
		int shown = 0;
		for (int i = 0; i < v.flips().size() && shown < 3; i++) {
			Flip f = v.flips().get(i);
			if (f.pct() < Math.max(0.10, cfg.minFlipProfitPercent / 100.0)) continue;
			if (!announced.add(f.c().uuid())) continue;
			shown++;
			Alerts.alert(HudFormatter.line("[SkyPrice] FLIP ", 0x55FF55, f.c().name(), HudFormatter.WHITE,
					"  buy " + HudFormatter.fmt(f.c().price()) + " → resell " + HudFormatter.fmt(f.resale()), HudFormatter.GRAY,
					String.format(Locale.ROOT, "  +%s (%.0f%%)", HudFormatter.fmt(f.profit()), f.pct() * 100), 0x55FF55,
					"  sells " + Market.eta(f.liq().etaHours()) + "  → /skyprice flip " + (i + 1), HudFormatter.AQUA));
		}
		if (announced.size() > 3000) announced.clear();
	}
}
