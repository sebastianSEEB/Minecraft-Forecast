package dev.sebastian.skyprice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ChestMenu;

/**
 * "FCF" button above your inventory. Click it to open a panel left of the inventory with your potential
 * free cash flow (market/fcf.py):
 *
 *   wallet + ongoing sales − ongoing bids + expected sale value (+ outbid refunds) = potential FCF
 *
 * Wallet uses your live purse from the sidebar plus the bank from the last report. Positions come from your
 * PC collector (/wallet), else the published wallet.json. Click a position to open that auction (you act yourself).
 */
final class FcfPanel {
	private static final int W = 168, BTN_W = 36, BTN_H = 14, ROW = 9, STEP = 12, POS_ROW = 19, MAX_POS = 8;
	private static final int BG = 0xE0101418, EDGE = 0xFF3987E5, MUTED = 0xFF8A8A8A, TEXT = 0xFFFFFFFF,
			POS = 0xFF3987E5, NEG = 0xFFE5534B, TOTAL = 0xFFB8B8B0, GOOD = 0xFF55FF55, GOLD = 0xFFFFAA00;

	private static SkyPriceConfig cfg;
	private static DataClient data;
	private static boolean open;
	private static volatile JsonObject report;
	private static volatile String source = "";
	private static volatile long fetchedAt, requestedAt;
	private static Double livePurse;
	private static long purseAt;
	private static final List<String> rowUuids = new ArrayList<>();
	private static int posTop;

	private FcfPanel() {}

	static void init(SkyPriceConfig config, DataClient client) {
		cfg = config;
		data = client;
	}

	/** Your inventory, and any chest-style menu (SkyBlock menu, Auction House, …). */
	private static boolean active(Screen s) {
		return cfg != null && cfg.fcfButton && (s instanceof InventoryScreen
				|| s instanceof AbstractContainerScreen<?> acs && acs.getMenu() instanceof ChestMenu);
	}

	private static int left(Screen s) {
		return GuiPos.box(s)[0];
	}

	private static int top(Screen s) {
		return GuiPos.box(s)[1];
	}

	private static int panelX(Screen s) {
		return Math.max(2, left(s) - W - 6);
	}

	// ------------------------------------------------------------------ data
	private static void refresh(boolean force) {
		long now = System.currentTimeMillis();
		if (!force && now - requestedAt < 60_000) return;
		requestedAt = now;
		data.walletReport().whenComplete((r, e) -> {
			if (r != null && r.json() != null) {
				report = r.json();
				source = r.source();
				fetchedAt = System.currentTimeMillis();
			}
		});
	}

	private static Double purse() {
		long now = System.currentTimeMillis();
		if (now - purseAt > 5000) {
			purseAt = now;
			try {
				Double p = PurseReporter.readPurse();
				if (p != null) livePurse = p;
			} catch (RuntimeException ignored) {
				// sidebar not ready
			}
		}
		return livePurse;
	}

	private static double d(JsonObject o, String k) {
		Double v = HudFormatter.num(o, k);
		return v == null ? 0 : v;
	}

	// ------------------------------------------------------------------ drawing
	static void paint(Screen s, Highlight.Painter p) {
		if (!active(s)) return;
		int bx = left(s), by = Math.max(1, top(s) - BTN_H - 2);
		p.fill(bx, by, bx + BTN_W, by + BTN_H, open ? 0xFF3987E5 : 0xF0202830);
		p.fill(bx, by, bx + BTN_W, by + 1, 0xFFFFAA00);
		p.fill(bx, by + BTN_H - 1, bx + BTN_W, by + BTN_H, 0xFFFFAA00);
		p.fill(bx, by, bx + 1, by + BTN_H, 0xFFFFAA00);
		p.fill(bx + BTN_W - 1, by, bx + BTN_W, by + BTN_H, 0xFFFFAA00);
		p.text("FCF ▸", bx + 5, by + 3, open ? TEXT : GOLD);
		if (!open) return;
		refresh(false);

		int x = panelX(s), y = top(s), w = Math.min(W, Math.max(120, left(s) - 8));
		JsonObject rep = report;
		JsonObject f = HudFormatter.obj(rep, "fcf");
		JsonObject n = HudFormatter.obj(f, "now");
		var font = Minecraft.getInstance().font;
		List<Runnable> draw = new ArrayList<>();
		int[] h = {16};

		// wallet: live purse + bank from the last report, else the report's wallet
		Double purse = purse();
		JsonObject wn = HudFormatter.obj(rep, "now");
		Double bank = wn == null ? null : (HudFormatter.num(wn, "bank") == null ? null : d(wn, "bank") + d(wn, "personal_bank"));
		Double repWallet = n == null ? null : HudFormatter.num(n, "wallet");
		Double wallet = purse != null ? purse + (bank == null ? 0 : bank) : repWallet;
		String walletNote = purse != null ? (bank == null ? "purse (live)" : "purse (live) + bank") : repWallet != null ? "purse + bank" : "unknown";

		if (n == null) {
			p.fill(x, y, x + w, y + 64, BG);
			p.fill(x, y, x + w, y + 1, EDGE);
			p.text("Potential FCF", x + 5, y + 4, GOLD);
			p.text("Wallet " + HudFormatter.fmt(wallet), x + 5, y + 18, TEXT);
			p.text(rep == null && fetchedAt == 0 ? "Loading…" : "No positions yet.", x + 5, y + 32, MUTED);
			p.text("Run the collector (run.py)", x + 5, y + 44, MUTED);
			return;
		}
		boolean subtract = n.has("bids_subtracted") && n.get("bids_subtracted").getAsBoolean();
		double sales = d(n, "sales"), bids = d(n, "bids"), expected = d(n, "expected"), refunds = d(n, "refunds");
		double w0 = wallet == null ? 0 : wallet;
		double fcf = w0 + sales - (subtract ? bids : 0) + expected + refunds;
		double cons = fcf - sales + d(n, "sales_conservative");

		// waterfall rows: label, delta, kind (0 total, 1 add, 2 subtract, 3 shown-only)
		record Step(String label, double v, int kind) {}
		List<Step> steps = new ArrayList<>();
		steps.add(new Step("Wallet", w0, 0));
		steps.add(new Step("+ Ongoing sales", sales, 1));
		steps.add(new Step("− Ongoing bids", -bids, subtract ? 2 : 3));
		steps.add(new Step("+ Expected sale value", expected, 1));
		if (!subtract) steps.add(new Step("+ Outbid refunds", refunds, 1));
		steps.add(new Step("= Potential FCF", fcf, 0));
		double max = Math.max(1, Math.max(fcf, w0 + sales + expected + refunds));

		int barX = x + 5, barW = w - 10;
		int ly = y + 16;
		for (Step st : steps) {
			final int ry = ly;
			draw.add(() -> stepText(p, st.label(), st.v(), st.kind(), barX, barW, ry));
			ly += STEP;
			h[0] += STEP;
		}
		// bars under each label (running total, like the dashboard waterfall)
		double run = 0;
		int by2 = y + 16 + ROW;
		List<int[]> bars = new ArrayList<>();
		for (Step st : steps) {
			double a, b;
			if (st.kind() == 0) { a = 0; b = st.v(); run = st.v(); }
			else if (st.kind() == 3) { a = run + st.v(); b = run; }
			else { a = run; b = run + st.v(); run += st.v(); }
			int x1 = barX + (int) Math.round(Math.min(a, b) / max * barW), x2 = barX + (int) Math.round(Math.max(a, b) / max * barW);
			int color = st.kind() == 0 ? TOTAL : st.kind() == 1 ? POS : st.kind() == 2 ? NEG : 0x80E5534B;
			bars.add(new int[]{Math.max(barX, x1), by2, Math.max(x1 + 1, Math.min(barX + barW, x2)), by2 + 2, color});
			by2 += STEP;
		}
		int consX = barX + (int) Math.round(Math.max(0, cons) / max * barW);

		h[0] += 22;                                                 // conservative + summary lines
		JsonElement pe = f.has("positions") ? f.get("positions") : null;
		List<JsonObject> pos = new ArrayList<>();
		int fixed = h[0] + 13 + 12;
		int room = Math.max(1, Math.min(MAX_POS, (s.height - 8 - fixed) / POS_ROW));
		int totalPos = 0;
		if (pe != null && pe.isJsonArray()) for (JsonElement e : pe.getAsJsonArray()) { totalPos++; if (pos.size() < room) pos.add(e.getAsJsonObject()); }
		h[0] += 13 + Math.max(1, pos.size()) * POS_ROW + 12;
		// move the panel up if it would run off the bottom of the screen (everything below is drawn relative to y)
		int shift = Math.max(0, y + h[0] - (s.height - 4));
		if (shift > 0) {
			int ny = Math.max(2, y - shift), dy = ny - y;
			y = ny;
			ly += dy;
			for (int[] b : bars) b[1] += dy;
			for (int[] b : bars) b[3] += dy;
			draw.clear();
			int ry0 = y + 16;
			for (Step st : steps) {
				final int ry = ry0;
				draw.add(() -> stepText(p, st.label(), st.v(), st.kind(), barX, barW, ry));
				ry0 += STEP;
			}
		}
		panelY = y;

		// background
		p.fill(x, y, x + w, y + h[0], BG);
		p.fill(x, y, x + w, y + 1, EDGE);
		p.fill(x, y + h[0] - 1, x + w, y + h[0], EDGE);
		p.text("Potential FCF", x + 5, y + 4, GOLD);
		String tw = HudFormatter.fmt(fcf);
		p.text(tw, x + w - 5 - font.width(tw), y + 4, GOOD);
		draw.forEach(Runnable::run);
		for (int[] b : bars) p.fill(b[0], b[1], b[2], b[3], b[4]);
		int lastBarY = bars.get(bars.size() - 1)[1];
		p.fill(consX, lastBarY - 2, consX + 1, lastBarY + 4, 0xFFFFFFFF);       // conservative marker

		int cy = ly;
		p.text(fit("conservative " + HudFormatter.fmt(cons) + "  (┃ marker)", barW), barX, cy, MUTED);
		cy += 10;
		p.text(fit("wallet: " + walletNote + (subtract ? "" : " · bids paid"), barW), barX, cy, MUTED);
		cy += 12;

		// positions
		p.fill(x + 4, cy, x + w - 4, cy + 1, 0xFF333333);
		p.text(fit("Positions" + (totalPos > pos.size() ? " " + pos.size() + "/" + totalPos : "") + " (click = open)", barW), barX, cy + 3, GOLD);
		cy += 13;
		rowUuids.clear();
		posTop = cy;
		if (pos.isEmpty()) {
			p.text("none in the last AH scan", barX, cy + 2, MUTED);
		}
		for (JsonObject o : pos) {
			String role = str(o, "role"), status = str(o, "status");
			String item = str(o, "item");
			if (item == null) item = str(o, "key");
			item = item == null ? "?" : item.replaceAll("§.", "");

			double amount = d(o, "amount");
			String right;
			int rc;
			if ("sale".equals(role)) {
				right = "→ " + HudFormatter.fmt(d(o, "net"));
				rc = 0xFF7FB4F0;
			} else if ("winning".equals(status)) {
				Double pr = HudFormatter.num(o, "profit");
				right = pr == null ? "winning" : (pr >= 0 ? "+" : "−") + HudFormatter.fmt(Math.abs(pr));
				rc = pr == null ? TEXT : pr >= 0 ? GOOD : NEG;
			} else {
				right = "refund " + HudFormatter.fmt(amount);
				rc = MUTED;
			}
			p.text(fit(item, barW - font.width(right) - 6), barX, cy + 1, TEXT);
			p.text(right, barX + barW - font.width(right), cy + 1, rc);
			boolean over = o.has("overpriced") && o.get("overpriced").getAsBoolean();
			String sub = ("sale".equals(role) ? "selling " : "bid ") + HudFormatter.fmt(amount) + " · " + status
					+ (over ? " · above value" : "");
			p.text(fit(sub, barW), barX, cy + 10, over ? 0xFFFFAA55 : MUTED);
			rowUuids.add(str(o, "uuid"));
			cy += POS_ROW;
		}
		long mins = fetchedAt == 0 ? -1 : (System.currentTimeMillis() - d(f, "updated") > 0 ? (long) ((System.currentTimeMillis() - d(f, "updated")) / 60000) : 0);
		p.text(fit((mins < 0 ? "" : "scan " + (mins < 1 ? "now" : mins + "m ago") + " · ") + source, barW - 12), barX,
				y + h[0] - 11, MUTED);
		p.text("⟳", barX + barW - 6, y + h[0] - 11, 0xFF55FFFF);
		lastHeight = h[0];
	}

	private static int lastHeight, panelY;

	private static void stepText(Highlight.Painter p, String label, double v, int kind, int x, int w, int y) {
		var font = Minecraft.getInstance().font;
		String val = kind == 0 ? HudFormatter.fmt(v) : (v >= 0 ? "+" : "−") + HudFormatter.fmt(Math.abs(v));
		p.text(label, x, y, kind == 0 ? TEXT : 0xFFCCCCCC);
		p.text(val, x + w - font.width(val), y, kind == 0 ? TEXT : v >= 0 ? 0xFF7FB4F0 : 0xFFFF8A80);
	}

	/** Cut text to fit the width, with an ellipsis. */
	private static String fit(String t, int maxW) {
		var font = Minecraft.getInstance().font;
		if (font.width(t) <= maxW) return t;
		while (t.length() > 1 && font.width(t + "…") > maxW) t = t.substring(0, t.length() - 1);
		return t + "…";
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
	}

	/** Returns true if the click hit the button or the panel. */
	static boolean click(Screen s, double mx, double my) {
		if (!active(s)) return false;
		int bx = left(s), by = Math.max(1, top(s) - BTN_H - 2);
		if (mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H) {
			open = !open;
			if (open) refresh(true);
			return true;
		}
		if (!open) return false;
		int x = panelX(s), y = panelY, w = Math.min(W, Math.max(120, left(s) - 8));
		if (mx < x || mx > x + w || my < y || my > y + lastHeight) return false;
		if (my >= y + lastHeight - 12) {                                  // footer: refresh now
			refresh(true);
			return true;
		}
		int i = (int) ((my - posTop) / POS_ROW);
		if (my >= posTop && i >= 0 && i < rowUuids.size() && rowUuids.get(i) != null) {
			Chat.command("viewauction " + rowUuids.get(i));               // you asked to open it; you act yourself
		}
		return true;
	}

	/** Chat version (/skyprice fcf). */
	static List<net.minecraft.network.chat.Component> chat(JsonObject rep, String src) {
		List<net.minecraft.network.chat.Component> out = new ArrayList<>();
		JsonObject n = HudFormatter.obj(rep, "fcf", "now");
		if (n == null) {
			out.add(HudFormatter.line("[SkyPrice] No FCF data yet: run the collector (run.py) with player_name set.", HudFormatter.GRAY));
			return out;
		}
		out.add(HudFormatter.line("━━ Potential FCF ", HudFormatter.GOLD, HudFormatter.fmt(HudFormatter.num(n, "fcf")), HudFormatter.GREEN,
				"  (conservative " + HudFormatter.fmt(HudFormatter.num(n, "fcf_conservative")) + ", " + src + ")", HudFormatter.DARK));
		out.add(HudFormatter.line(String.format(Locale.ROOT, " wallet %s + sales %s − bids %s%s + expected %s + refunds %s",
				HudFormatter.fmt(HudFormatter.num(n, "wallet")), HudFormatter.fmt(HudFormatter.num(n, "sales")),
				HudFormatter.fmt(HudFormatter.num(n, "bids")),
				n.has("bids_subtracted") && n.get("bids_subtracted").getAsBoolean() ? "" : " (already paid)",
				HudFormatter.fmt(HudFormatter.num(n, "expected")), HudFormatter.fmt(HudFormatter.num(n, "refunds"))), HudFormatter.GRAY));
		out.add(HudFormatter.line(" Open the FCF button above your inventory (E) for the full breakdown.", HudFormatter.DARK));
		return out;
	}
}
