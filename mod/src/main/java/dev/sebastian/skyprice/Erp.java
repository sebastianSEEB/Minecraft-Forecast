package dev.sebastian.skyprice;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * ERP mode: the "ERP" button above the inventory turns the screen into a console laid out like the
 * RealGame / BioCounter plant (status column with clock and PROFIT / CASH gauges, orange icon bar,
 * one window at a time), with every module mapped to your SkyBlock trading:
 *
 *   Inventory          everything you have on the AH (listings, winning and outbid bids)
 *   Purchases          your bids = purchase orders (your bid, top bid, value, profit)
 *   Sales offers       your BIN / auction listings (price vs value, after the claim tax)
 *   Forthcoming events auctions ending, soonest first
 *   Collect            ended auctions and outbid coins waiting to be claimed (Deliver incoming orders)
 *   Cash flow          potential FCF (Investments & Funding → future cash flow)
 *   BI                 FCF trend, where your coins are tied up, income-statement style report
 *   Coach              live warnings (overpriced listings, outbid, ending soon, too much tied up)
 *   Messages           outbid notifications from chat
 *   Bazaar             your selected bazaar products (default: Stock of Stonks + candy) with live prices and a 7-day chart
 *
 * Clicks inside ERP never reach the inventory. Clicking an auction row opens it with /viewauction; you act yourself.
 */
final class Erp {
	// RealGame palette (light theme)
	private static final int BG = 0xFFF1F1F1, PANEL = 0xFFFFFFFF, INK = 0xFF1F1F1F, MUTED = 0xFF6F6F6F, LINE = 0xFFE1E1E1,
			ORANGE = 0xFFFF5A00, GREYHEAD = 0xFF8D8D8D, GREEN = 0xFF2EA44F, RED = 0xFFD23A2A, BLUE = 0xFF1E74D6, PEACH = 0xFFFFD8C5,
			LILAC = 0xFFA093D8, GOLDR = 0xFFFFD35C, PEACHRING = 0xFFFFB98F, YELLOW = 0xFFE2C418;
	static final String[] DEFAULT_BAZAAR = {"STOCK_OF_STONKS", "ULTIMATE_CARROT_CANDY", "SUPERB_CARROT_CANDY", "GREAT_CARROT_CANDY",
			"SIMPLE_CARROT_CANDY", "PURPLE_CANDY", "GREEN_CANDY", "DARK_CANDY", "ROCK_CANDY"};

	private record Btn(int x1, int y1, int x2, int y2, Runnable action) {}

	private static SkyPriceConfig cfg;
	private static DataClient data;
	private static boolean on;
	private static String win = "inventory";
	private static String biView = "general";
	private static boolean biWeek = true;
	private static int page;
	private static String bzSel;
	private static final List<Btn> buttons = new ArrayList<>();

	private Erp() {}

	static void init(SkyPriceConfig config, DataClient client) {
		cfg = config;
		data = client;
	}

	static boolean isOn() {
		return on;
	}

	static void toggle() {
		on = !on;
		page = 0;
		if (on) FcfPanel.refresh(true);
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static int w(String t) {
		return mc().font.width(t);
	}

	private static void btn(int x1, int y1, int x2, int y2, Runnable r) {
		buttons.add(new Btn(x1, y1, x2, y2, r));
	}

	private static void open(String id) {
		win = id;
		page = 0;
	}

	// ================================================================== entry points
	static void paint(Screen s, Highlight.Painter p) {
		if (!on) return;
		buttons.clear();
		FcfPanel.refresh(false);
		int W = s.width, H = s.height;
		p.fill(0, 0, W, H, BG);
		JsonObject rep = FcfPanel.report();
		JsonObject f = HudFormatter.obj(rep, "fcf");
		JsonObject n = HudFormatter.obj(f, "now");
		List<JsonObject> pos = positions(f);

		int colW = Math.min(112, Math.max(90, W / 5));
		status(p, 6, 4, colW, H, n, pos);
		int deskX = colW + 12, deskW = W - deskX - 6;
		topbar(p, deskX, deskW, pos);
		int wy = 26;
		window(p, deskX, wy, deskW, H - wy - 6, f, n, pos);
	}

	/** Returns true when ERP is on (the click is used here and must not reach the inventory). */
	static boolean click(double mx, double my) {
		if (!on) return false;
		for (int i = buttons.size() - 1; i >= 0; i--) {
			Btn b = buttons.get(i);
			if (mx >= b.x1() && mx < b.x2() && my >= b.y1() && my < b.y2()) {
				b.action().run();
				break;
			}
		}
		return true;
	}

	// ================================================================== data
	private static List<JsonObject> positions(JsonObject f) {
		List<JsonObject> out = new ArrayList<>();
		JsonElement pe = f == null || !f.has("positions") ? null : f.get("positions");
		if (pe != null && pe.isJsonArray()) for (JsonElement e : pe.getAsJsonArray()) out.add(e.getAsJsonObject());
		return out;
	}

	private static double d(JsonObject o, String k) {
		return FcfPanel.d(o, k);
	}

	private static String str(JsonObject o, String k) {
		return o == null ? null : FcfPanel.str(o, k);
	}

	private static String item(JsonObject o) {
		String it = str(o, "item");
		if (it == null) it = str(o, "key");
		return it == null ? "?" : it.replaceAll("§.", "");
	}

	private static boolean sale(JsonObject o) {
		return "sale".equals(str(o, "role"));
	}

	private static boolean winning(JsonObject o) {
		return "winning".equals(str(o, "status"));
	}

	private static String fmt(double v) {
		return HudFormatter.fmt(v);
	}

	private static String signed(double v) {
		return (v >= 0 ? "+" : "−") + fmt(Math.abs(v));
	}

	private static String ends(JsonObject o) {
		Double e = HudFormatter.num(o, "end");
		if (e == null) return "";
		long mins = (long) ((e - System.currentTimeMillis()) / 60000);
		if (mins <= 0) return "ended";
		return mins < 60 ? mins + "m" : mins < 48 * 60 ? (mins / 60) + "h " + (mins % 60) + "m" : (mins / 1440) + "d";
	}

	private static Double walletNow(JsonObject n) {
		Double purse = FcfPanel.purse();
		JsonObject wn = HudFormatter.obj(FcfPanel.report(), "now");
		Double bank = wn == null || HudFormatter.num(wn, "bank") == null ? null : d(wn, "bank") + d(wn, "personal_bank");
		if (purse != null) return purse + (bank == null ? 0 : bank);
		return n == null ? null : HudFormatter.num(n, "wallet");
	}

	// ================================================================== status column (clock + gauges)
	private static void status(Highlight.Painter p, int x, int y, int w, int H, JsonObject n, List<JsonObject> pos) {
		String team = mc().player == null ? "SkyPrice" : mc().player.getName().getString();
		p.text(FcfPanel.fit(team, w), x, y + 2, ORANGE);
		LocalDateTime now = LocalDateTime.now();
		String time = now.format(DateTimeFormatter.ofPattern("H:mm"));
		int hr = now.getHour();
		p.text(hr >= 8 && hr < 16 ? "☀" : hr >= 21 || hr < 5 ? "☾" : "◒", x, y + 16, hr >= 21 || hr < 5 ? 0xFF8A8A8A : ORANGE);
		p.text(time, x + w - w(time), y + 16, ORANGE);
		String date = now.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)) + suffix(now.getDayOfMonth()) + " " + now.getYear();
		p.text(date, x + w - w(date), y + 27, ORANGE);

		double profit = n == null ? 0 : d(n, "expected_profit_on_wins");
		double bids = n == null ? 0 : d(n, "bids");
		double margin = bids > 0 ? profit / bids : 0;                 // gauge: expected margin on what you're winning, 0–50 %
		int gy = y + 42;
		gauge(p, x + w / 2, gy + 22, 20, Math.max(0, Math.min(1, margin / 0.5)));
		p.text("PROFIT", x, gy + 27, ORANGE);
		p.fill(x, gy + 37, x + w, gy + 38, ORANGE);
		String pv = signed(profit);
		p.text(pv, x + w - w(pv), gy + 40, profit >= 0 ? GREEN : RED);

		Double wallet = walletNow(n);
		double tiedUp = n == null ? 0 : d(n, "sales") + d(n, "bids") + d(n, "refunds");
		double liquid = wallet == null ? 0 : wallet / Math.max(1, wallet + tiedUp);   // gauge: share of your coins that are free cash
		gy += 54;
		gauge(p, x + w / 2, gy + 22, 20, liquid);
		p.text("CASH", x, gy + 27, ORANGE);
		p.fill(x, gy + 37, x + w, gy + 38, ORANGE);
		String cv = wallet == null ? "?" : fmt(wallet);
		p.text(cv, x + w - w(cv), gy + 40, wallet != null && wallet < 0 ? RED : GREEN);

		gy += 56;
		long mins = FcfPanel.fetchedAt() == 0 ? -1 : (long) ((System.currentTimeMillis() - d(HudFormatter.obj(FcfPanel.report(), "fcf"), "updated")) / 60000);
		p.text(FcfPanel.fit(mins < 0 ? "no AH scan yet" : "AH scan " + (mins < 1 ? "now" : mins + " min ago"), w), x, gy, MUTED);
		gy += 13;
		String[][] quick = {{"inventory", "Inventory"}, {"purchases", "Purchases"}, {"events", "Forthcoming events"},
				{"collect", "Collect auctions"}, {"sales", "Sales offers"}, {"bazaar", "Bazaar prices"}};
		for (String[] q : quick) {
			if (gy > H - 12) break;
			p.text(FcfPanel.fit(q[1], w), x, gy, BLUE);
			final String id = q[0];
			btn(x, gy - 1, x + w, gy + 9, () -> open(id));
			gy += 11;
		}
	}

	private static String suffix(int d) {
		if (d >= 11 && d <= 13) return "th";
		return switch (d % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
	}

	/** RealGame-style half gauge: lilac / gold / peach bands, orange rim, needle at value 0..1. */
	private static void gauge(Highlight.Painter p, int cx, int cy, int r, double v) {
		int rin = r - 7;
		for (int dy = -r; dy <= 0; dy++) {
			int runStart = 0, runColor = -1;
			for (int dx = -r - 2; dx <= r + 3; dx++) {
				double dist = Math.sqrt(dx * dx + dy * dy);
				int color = -1;
				if (dx <= r + 2) {
					double a = Math.atan2(-dy, -dx);                       // 0 left … π right
					if (dist <= r && dist >= rin) color = a < Math.PI / 3 ? LILAC : a < 2 * Math.PI / 3 ? GOLDR : PEACHRING;
					else if (dist > r + 1 && dist <= r + 2.2) color = ORANGE;
				}
				if (color != runColor) {
					if (runColor != -1) p.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, runColor);
					runStart = dx;
					runColor = color;
				}
			}
		}
		double a = Math.PI * Math.max(0, Math.min(1, v));
		for (int i = 0; i <= rin; i++) {
			int px = cx - (int) Math.round(Math.cos(a) * i), py = cy - (int) Math.round(Math.sin(a) * i);
			p.fill(px - 1, py - 1, px + 1, py + 1, ORANGE);
		}
		p.fill(cx - 3, cy - 3, cx + 3, cy + 3, ORANGE);
		p.fill(cx - 2, cy - 2, cx + 2, cy + 2, PANEL);
	}

	// ================================================================== top bar
	private static void topbar(Highlight.Painter p, int x, int w, List<JsonObject> pos) {
		String[][] items = {{"bazaar", "Bazaar"}, {"cashflow", "Cash flow"}, {"sales", "Sales"}, {"collect", "Collect"},
				{"messages", "Mail"}, {"coach", "Coach"}, {"bi", "BI"}, {"inventory", "Inventory"}};
		int total = 0;
		for (String[] it : items) total += w(it[1]) + 10;
		total += w("✕ Exit ERP") + 12;
		int bx = x + Math.max(0, (w - total) / 2), bw = Math.min(w, total + 12);
		p.fill(bx - 6, 0, bx - 6 + bw, 18, ORANGE);
		p.fill(bx - 4, 18, bx - 8 + bw, 20, ORANGE);                     // rounded bottom edge
		int cx = bx;
		int collect = (int) pos.stream().filter(o -> "outbid".equals(str(o, "status")) || o.has("ended") && o.get("ended").getAsBoolean()).count();
		int coachBad = (int) coach(HudFormatter.obj(HudFormatter.obj(FcfPanel.report(), "fcf"), "now"), pos).stream().filter(c -> c[0].equals("bad")).count();
		int mail = OutbidHud.list().size();
		for (String[] it : items) {
			int tw = w(it[1]);
			boolean sel = win.equals(it[0]);
			if (sel) p.fill(cx - 2, 2, cx + tw + 4, 16, 0x40FFFFFF);
			p.text(it[1], cx + 1, 5, 0xFFFFFFFF);
			int badge = it[0].equals("collect") ? collect : it[0].equals("coach") ? coachBad : it[0].equals("messages") ? mail : 0;
			if (badge > 0) {
				String b = String.valueOf(badge);
				p.fill(cx + tw - 1, 0, cx + tw + w(b) + 3, 8, BLUE);
				p.text(b, cx + tw + 1, 0, 0xFFFFFFFF);
			}
			final String id = it[0];
			btn(cx - 2, 0, cx + tw + 6, 18, () -> open(id));
			cx += tw + 10;
		}
		p.text("✕ Exit ERP", cx + 2, 5, 0xFFFFFFFF);
		btn(cx, 0, cx + w("✕ Exit ERP") + 8, 18, () -> on = false);
	}

	// ================================================================== window frame
	private static final String[][] TITLES = {{"inventory", "Inventory"}, {"purchases", "Purchases"}, {"sales", "Sales offers"},
			{"events", "Forthcoming events"}, {"collect", "Deliver / collect auctions"}, {"cashflow", "Investments & Funding"},
			{"bi", "Business Intelligence"}, {"coach", "Coach"}, {"messages", "Messages"}, {"bazaar", "Bazaar prices"}};

	private static void window(Highlight.Painter p, int x, int y, int w, int h, JsonObject f, JsonObject n, List<JsonObject> pos) {
		String title = win;
		for (String[] t : TITLES) if (t[0].equals(win)) title = t[1];
		boolean bi = win.equals("bi");
		if (bi) {                                                         // BI window: orange outline, white header
			p.fill(x - 2, y - 2, x + w + 2, y + h + 2, ORANGE);
			p.fill(x, y, x + w, y + h, PANEL);
			p.text(title, x + (w - w(title)) / 2, y + 4, ORANGE);
		} else {
			p.fill(x + 1, y + h, x + w + 1, y + h + 1, 0xFFCCCCCC);         // shadow
			p.fill(x, y, x + w, y + h, PANEL);
			p.fill(x, y, x + w, y + 14, ORANGE);
			p.text(title, x + 6, y + 3, 0xFFFFFFFF);
		}
		p.text("✕", x + w - 11, y + 3, bi ? ORANGE : 0xFFFFFFFF);
		btn(x + w - 14, y, x + w, y + 14, () -> open("inventory"));
		int bx = x + 6, by = y + 18, bw = w - 12, bh = h - 22;
		switch (win) {
			case "purchases" -> purchases(p, bx, by, bw, bh, pos);
			case "sales" -> sales(p, bx, by, bw, bh, pos);
			case "events" -> events(p, bx, by, bw, bh, pos);
			case "collect" -> collect(p, bx, by, bw, bh, pos);
			case "cashflow" -> cashflow(p, bx, by, bw, bh, n);
			case "bi" -> bi(p, bx, by, bw, bh, f, n, pos);
			case "coach" -> coachWin(p, bx, by, bw, bh, n, pos);
			case "messages" -> messages(p, bx, by, bw, bh);
			case "bazaar" -> bazaar(p, bx, by, bw, bh);
			default -> inventory(p, bx, by, bw, bh, pos, n);
		}
		if (n == null && !win.equals("bazaar") && !win.equals("messages")) {
			p.text(FcfPanel.report() == null ? "Loading your auctions…" : "No auctions found yet (the AH is scanned every 15 min).", bx, by + 30, MUTED);
		}
	}

	// ================================================================== table helper
	/** cols: header text; align: true = right-aligned; width share (fractions of the table width). */
	private record Col(String head, double share, boolean right) {}

	private record Row(String[] cells, int[] colors, String badge, int badgeColor, Runnable onClick) {}

	private static void table(Highlight.Painter p, int x, int y, int w, int h, Col[] cols, List<Row> rows, String foot) {
		int[] cx = new int[cols.length + 1];
		double acc = 0;
		for (int i = 0; i < cols.length; i++) {
			cx[i] = x + (int) Math.round(acc * w);
			acc += cols[i].share();
		}
		cx[cols.length] = x + w;
		for (int i = 0; i < cols.length; i++) {
			String hd = cols[i].head();
			int tx = cols[i].right() ? cx[i + 1] - 3 - w(hd) : cx[i] + 2;
			p.text(hd, tx, y, INK);
		}
		p.fill(x, y + 10, x + w, y + 11, LINE);
		int rowH = 11, avail = h - 14 - (foot != null ? 12 : 0) - 10;
		int per = Math.max(1, avail / rowH), pages = Math.max(1, (rows.size() + per - 1) / per);
		page = Math.min(page, pages - 1);
		int ry = y + 13;
		for (int r = page * per; r < Math.min(rows.size(), (page + 1) * per); r++) {
			Row row = rows.get(r);
			for (int i = 0; i < cols.length; i++) {
				String c = row.cells()[i] == null ? "" : row.cells()[i];
				int colW = cx[i + 1] - cx[i] - 5;
				int tx = cx[i] + 2;
				if (i == 0 && row.badge() != null) {
					p.fill(tx, ry, tx + 8, ry + 8, row.badgeColor());
					p.text(row.badge(), tx + 2, ry, 0xFFFFFFFF);
					tx += 11;
					colW -= 11;
				}
				c = FcfPanel.fit(c, colW);
				if (cols[i].right()) tx = cx[i + 1] - 3 - w(c);
				p.text(c, tx, ry, row.colors() == null || row.colors()[i] == 0 ? INK : row.colors()[i]);
			}
			p.fill(x, ry + 9, x + w, ry + 10, LINE);
			if (row.onClick() != null) btn(x, ry - 1, x + w, ry + 9, row.onClick());
			ry += rowH;
		}
		if (rows.isEmpty()) p.text("Nothing here right now.", x + 2, ry + 2, MUTED);
		int fy = y + h - 10;
		if (foot != null) p.text(FcfPanel.fit(foot, w - 50), x, fy, INK);
		if (pages > 1) {
			String pg = "▲ " + (page + 1) + "/" + pages + " ▼";
			int px = x + w - w(pg);
			p.text(pg, px, fy, BLUE);
			btn(px - 2, fy - 2, px + 8, fy + 9, () -> page = Math.max(0, page - 1));
			btn(px + w(pg) - 10, fy - 2, px + w(pg) + 2, fy + 9, () -> page++);
		}
	}

	private static Runnable view(JsonObject o) {
		String uuid = str(o, "uuid");
		return uuid == null ? null : () -> Chat.command("viewauction " + uuid);
	}

	// ================================================================== modules
	private static void inventory(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos, JsonObject n) {
		List<Row> rows = new ArrayList<>();
		for (JsonObject o : pos) {
			boolean s = sale(o), win = winning(o);
			Double val = HudFormatter.num(o, "value");
			rows.add(new Row(new String[]{item(o), String.valueOf((int) d(o, "count")), fmt(d(o, "amount")), val == null ? "-" : fmt(val),
					s ? str(o, "status") : win ? "winning bid" : "outbid"},
					new int[]{0, 0, 0, 0, s ? 0 : win ? GREEN : MUTED}, s ? "L" : win ? "W" : "O", s ? 0xFF111111 : win ? YELLOW : 0xFF3D8BD9, view(o)));
		}
		double tied = n == null ? 0 : d(n, "sales") + d(n, "bids") + d(n, "refunds");
		table(p, x, y, w, h, new Col[]{new Col("Item", 0.40, false), new Col("Amount", 0.10, true), new Col("Avg cost", 0.16, true),
				new Col("Value", 0.16, true), new Col("Status", 0.18, false)}, rows,
				"(L)isted  (W)inning  (O)utbid · coins tied up " + fmt(tied));
	}

	private static void purchases(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos) {
		List<Row> rows = new ArrayList<>();
		double total = 0;
		for (JsonObject o : pos) {
			if (sale(o)) continue;
			boolean win = winning(o);
			Double pr = HudFormatter.num(o, "profit");
			if (win) total += d(o, "amount");
			rows.add(new Row(new String[]{item(o), fmt(d(o, "amount")), fmt(d(o, "top_bid")), HudFormatter.num(o, "value") == null ? "-" : fmt(d(o, "value")),
					pr == null ? (win ? "?" : "refund") : signed(pr), ends(o)},
					new int[]{0, 0, 0, 0, pr == null ? MUTED : pr >= 0 ? GREEN : RED, 0}, win ? "W" : "O", win ? YELLOW : 0xFF3D8BD9, view(o)));
		}
		table(p, x, y, w, h, new Col[]{new Col("Supplier item", 0.34, false), new Col("Your bid", 0.14, true), new Col("Top bid", 0.14, true),
				new Col("Value", 0.13, true), new Col("Profit", 0.13, true), new Col("Ends", 0.12, true)}, rows,
				"Winning bids total " + fmt(total) + " · bid coins leave your purse when you bid");
	}

	private static void sales(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos) {
		List<Row> rows = new ArrayList<>();
		double total = 0;
		for (JsonObject o : pos) {
			if (!sale(o)) continue;
			boolean over = o.has("overpriced") && o.get("overpriced").getAsBoolean();
			total += d(o, "net");
			rows.add(new Row(new String[]{item(o), fmt(d(o, "amount")), HudFormatter.num(o, "value") == null ? "-" : fmt(d(o, "value")),
					fmt(d(o, "net")), over ? "above value" : str(o, "status"), ends(o)},
					new int[]{0, 0, 0, 0, over ? RED : MUTED, 0}, o.has("bin") && o.get("bin").getAsBoolean() ? "B" : "A", 0xFFF26A1B, view(o)));
		}
		table(p, x, y, w, h, new Col[]{new Col("Offer", 0.33, false), new Col("Price", 0.13, true), new Col("Value", 0.13, true),
				new Col("After tax", 0.14, true), new Col("Status", 0.15, false), new Col("Ends", 0.12, true)}, rows,
				"Sales value " + fmt(total) + " after the 1% claim tax (>1M) · (B)IN  (A)uction");
	}

	private static void events(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos) {
		List<JsonObject> sorted = new ArrayList<>(pos);
		sorted.sort((a, b) -> Double.compare(d(a, "end"), d(b, "end")));
		List<Row> rows = new ArrayList<>();
		DateTimeFormatter hm = DateTimeFormatter.ofPattern("EEE H:mm", Locale.ENGLISH);
		for (JsonObject o : sorted) {
			Double e = HudFormatter.num(o, "end");
			String when = e == null ? "" : java.time.Instant.ofEpochMilli(e.longValue()).atZone(java.time.ZoneId.systemDefault()).format(hm);
			boolean soon = e != null && e - System.currentTimeMillis() < 3_600_000L;
			String act = sale(o) ? "Your auction ends" : winning(o) ? "You win (if not outbid)" : "Auction ends (outbid)";
			rows.add(new Row(new String[]{when + " (" + ends(o) + ")", act, fmt(d(o, "amount")), item(o)},
					new int[]{soon ? RED : 0, 0, 0, 0}, null, 0, view(o)));
		}
		table(p, x, y, w, h, new Col[]{new Col("Time", 0.28, false), new Col("Action", 0.27, false), new Col("Amount", 0.13, true),
				new Col("Description", 0.32, false)}, rows, "Red = ending within an hour");
	}

	private static void collect(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos) {
		List<Row> rows = new ArrayList<>();
		double total = 0;
		for (JsonObject o : pos) {
			boolean ended = o.has("ended") && o.get("ended").getAsBoolean();
			boolean outbid = "outbid".equals(str(o, "status"));
			if (!ended && !outbid) continue;
			String act = outbid ? "Collect refund" : sale(o) ? "Collect coins" : "Claim item";
			double amt = outbid ? d(o, "amount") : sale(o) ? d(o, "net") : d(o, "value");
			total += amt;
			rows.add(new Row(new String[]{ended ? "ended" : ends(o), item(o), act, fmt(amt)}, new int[]{ended ? GREEN : 0, 0, 0, 0},
					null, 0, view(o)));
		}
		table(p, x, y, w, h, new Col[]{new Col("Status", 0.18, false), new Col("Item", 0.40, false), new Col("Action", 0.24, false),
				new Col("Amount", 0.18, true)}, rows, "To collect: " + fmt(total) + " · click a row to open it");
	}

	private static void cashflow(Highlight.Painter p, int x, int y, int w, int h, JsonObject n) {
		if (n == null) return;
		p.text("FUTURE CASH FLOW", x, y, BLUE);
		p.fill(x, y + 10, x + w("FUTURE CASH FLOW"), y + 11, BLUE);
		Double wallet = walletNow(n);
		boolean subtract = n.has("bids_subtracted") && n.get("bids_subtracted").getAsBoolean();
		double w0 = wallet == null ? 0 : wallet, sales = d(n, "sales"), bids = d(n, "bids"), exp = d(n, "expected"), ref = subtract ? 0 : d(n, "refunds");
		double fcf = w0 + sales - (subtract ? bids : 0) + exp + ref, cons = fcf - sales + d(n, "sales_conservative");
		Object[][] steps = {{"Cash (purse + bank)", w0, 0}, {"+ Ongoing sales", sales, 1}, {"− Ongoing bids" + (subtract ? "" : " (paid)"), -bids, subtract ? 2 : 3},
				{"+ Expected sale value", exp, 1}, {"+ Outbid refunds", ref, 1}, {"= Potential FCF", fcf, 0}};
		double max = Math.max(1, Math.max(fcf, w0 + sales + exp + ref));
		int labW = Math.min(130, w / 3), barX = x + labW + 6, barW = w - labW - 60;
		int ry = y + 18;
		double run = 0;
		for (Object[] st : steps) {
			String lab = (String) st[0];
			double v = (double) st[1];
			int kind = (int) st[2];
			double a, b;
			if (kind == 0) { a = 0; b = v; run = v; } else if (kind == 3) { a = run + v; b = run; } else { a = run; b = run + v; run += v; }
			p.text(FcfPanel.fit(lab, labW), x, ry, kind == 0 ? INK : MUTED);
			int x1 = barX + (int) (Math.min(a, b) / max * barW), x2 = barX + (int) (Math.max(a, b) / max * barW);
			int color = kind == 0 ? 0xFF8D8D8D : kind == 1 ? 0xFF4CAF50 : kind == 2 ? 0xFFFF8A3D : 0x80FF8A3D;
			p.fill(x1, ry, Math.max(x1 + 1, x2), ry + 8, color);
			String val = kind == 0 ? fmt(v) : signed(v);
			p.text(val, x + w - w(val), ry, kind == 0 ? INK : v >= 0 ? GREEN : RED);
			ry += 14;
		}
		int consX = barX + (int) (Math.max(0, cons) / max * barW);
		p.fill(consX, ry - 16, consX + 1, ry - 4, INK);
		ry += 4;
		p.text("Conservative (listings at value): " + fmt(cons), x, ry, INK);
		ry += 12;
		p.text(FcfPanel.fit(subtract ? "Bids are subtracted from cash." : "Bid coins already left your purse, so they are shown, not subtracted again.", w), x, ry, MUTED);
		ry += 11;
		p.text(FcfPanel.fit("Only booked items: listings at their price, winning items at their estimated value.", w), x, ry, MUTED);
	}

	private static void bi(Highlight.Painter p, int x, int y, int w, int h, JsonObject f, JsonObject n, List<JsonObject> pos) {
		String[][] menu = {{"general", "General"}, {"shares", "Market Shares"}, {"reports", "Reports"}};
		int mw = 70, my = y + 2;
		for (String[] m : menu) {
			boolean sel = biView.equals(m[0]);
			if (sel) p.fill(x, my - 2, x + mw, my + 10, 0xFFFFDCCB);
			p.text(m[1], x + 4, my, INK);
			final String id = m[0];
			btn(x, my - 2, x + mw, my + 10, () -> biView = id);
			my += 15;
		}
		int cx = x + mw + 8, cw = w - mw - 8;
		if (f == null || n == null) return;
		switch (biView) {
			case "shares" -> biShares(p, cx, y, cw, h, n, pos);
			case "reports" -> biReport(p, cx, y, cw, h, n, pos);
			default -> biGeneral(p, cx, y, cw, h, f, n);
		}
	}

	private static void biGeneral(Highlight.Painter p, int x, int y, int w, int h, JsonObject f, JsonObject n) {
		p.text("KPI: Potential FCF", x, y, INK);
		String r1 = "last week", r2 = "last 24h";
		int rx = x + w - w(r1) - w(r2) - 14;
		p.fill(rx - 2, y - 2, rx + w(r1) + 2, y + 9, biWeek ? 0xFFFFDCCB : 0xFFF3F3F3);
		p.text(r1, rx, y, INK);
		btn(rx - 2, y - 2, rx + w(r1) + 2, y + 9, () -> biWeek = true);
		int rx2 = rx + w(r1) + 8;
		p.fill(rx2 - 2, y - 2, rx2 + w(r2) + 2, y + 9, biWeek ? 0xFFF3F3F3 : 0xFFFFDCCB);
		p.text(r2, rx2, y, INK);
		btn(rx2 - 2, y - 2, rx2 + w(r2) + 2, y + 9, () -> biWeek = false);

		long now = System.currentTimeMillis(), from = now - (biWeek ? 7 : 1) * 86_400_000L;
		List<long[]> fcfPts = new ArrayList<>(), ahPts = new ArrayList<>();
		JsonObject wn = HudFormatter.obj(FcfPanel.report(), "now");
		Double bank = wn == null || HudFormatter.num(wn, "bank") == null ? null : d(wn, "bank") + d(wn, "personal_bank");
		JsonElement se = f.has("series") ? f.get("series") : null;
		if (se != null && se.isJsonArray()) {
			for (JsonElement e : se.getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				Double tsD = HudFormatter.num(o, "ts");
				if (tsD == null || tsD < from) continue;
				long ts = tsD.longValue();
				Double fc = HudFormatter.num(o, "fcf"), ah = HudFormatter.num(o, "on_ah");
				if (ah == null && fc != null && HudFormatter.num(o, "wallet") != null) ah = fc - d(o, "wallet");
				if (fc == null && ah != null) {
					Double pu = PurseLog.at(ts);
					if (pu != null) fc = ah + pu + (bank == null ? 0 : bank);
				}
				if (fc != null) fcfPts.add(new long[]{ts, Math.round(fc)});
				if (ah != null) ahPts.add(new long[]{ts, Math.round(ah)});
			}
		}
		Double wallet = walletNow(n);
		if (wallet != null) fcfPts.add(new long[]{now, Math.round(wallet + d(n, "on_ah"))});
		ahPts.add(new long[]{now, Math.round(d(n, "on_ah"))});
		// legend
		int ly = y + 14;
		p.fill(x, ly + 3, x + 16, ly + 6, 0xFF9467BD);
		p.text("Potential FCF", x + 20, ly, INK);
		p.fill(x + 90, ly + 3, x + 106, ly + 6, 0xFFFF7F0E);
		p.text("On the AH", x + 110, ly, INK);
		long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
		for (List<long[]> l : List.of(fcfPts, ahPts)) for (long[] q : l) { lo = Math.min(lo, q[1]); hi = Math.max(hi, q[1]); }
		if (lo == Long.MAX_VALUE) return;
		long pad = Math.max(1, (hi - lo) / 10);
		lo = Math.max(0, lo - pad);
		hi += pad;
		int gx = x + 34, gy = ly + 14, gw = w - 38, gh = h - (gy - y) - 24;
		for (int k = 0; k <= 4; k++) {
			long v = lo + (hi - lo) * k / 4;
			int yy = gy + gh - (int) ((double) (v - lo) / (hi - lo) * gh);
			p.fill(gx, yy, gx + gw, yy + 1, 0xFFE6E6E6);
			String t = fmt((double) v);
			p.text(t, gx - 3 - w(t), yy - 4, MUTED);
		}
		p.text(biWeek ? "7 days ago" : "24h ago", gx, gy + gh + 3, MUTED);
		p.text("now", gx + gw - w("now"), gy + gh + 3, MUTED);
		FcfPanel.line(p, ahPts, from, now, lo, hi, gx, gy, gw, gh, 0xFFFF7F0E);
		FcfPanel.line(p, fcfPts, from, now, lo, hi, gx, gy, gw, gh, 0xFF9467BD);
		p.text("Data is updated with every AH scan", x + (w - w("Data is updated with every AH scan")) / 2, y + h - 10, BLUE);
	}

	private static void biShares(Highlight.Painter p, int x, int y, int w, int h, JsonObject n, List<JsonObject> pos) {
		p.text("Where your coins are", x, y, INK);
		Double purse = FcfPanel.purse();
		JsonObject wn = HudFormatter.obj(FcfPanel.report(), "now");
		double bank = wn == null ? d(n, "bank") : d(wn, "bank") + d(wn, "personal_bank");
		double pu = purse != null ? purse : HudFormatter.num(n, "purse") != null ? d(n, "purse") : 0;
		boolean subtract = n.has("bids_subtracted") && n.get("bids_subtracted").getAsBoolean();
		String[] labels = {"Purse", "Bank", "Listed items", "In winning bids", "Outbid (to collect)"};
		double[] v = {pu, bank, d(n, "sales"), d(n, "bids"), subtract ? 0 : d(n, "refunds")};
		double total = 0;
		for (double a : v) total += Math.max(0, a);
		int r = Math.min(46, (h - 30) / 2);
		int cx = x + r + 4, cy = y + 16 + r;
		if (total > 0) FcfPanel.pie(p, cx, cy, r, v, total);
		int lx = cx + r + 14, ly = y + 18;
		for (int i = 0; i < labels.length; i++) {
			p.fill(lx, ly + 1, lx + 16, ly + 7, FcfPanel.SLICE[i]);
			p.text(labels[i], lx + 20, ly, INK);
			String val = fmt(v[i]) + String.format(Locale.ROOT, "  %.0f%%", total > 0 ? 100 * Math.max(0, v[i]) / total : 0);
			p.text(val, x + w - w(val), ly, INK);
			ly += 13;
		}
		p.fill(lx, ly, x + w, ly + 1, LINE);
		p.text("Total", lx + 20, ly + 3, INK);
		p.text(fmt(total), x + w - w(fmt(total)), ly + 3, INK);
		ly += 18;
		List<JsonObject> top = new ArrayList<>(pos);
		top.sort((a, b) -> Double.compare(FcfPanel.tied(b), FcfPanel.tied(a)));
		p.text("Biggest positions", lx, ly, MUTED);
		ly += 11;
		for (int i = 0; i < Math.min(4, top.size()) && ly < y + h - 10; i++) {
			JsonObject o = top.get(i);
			String val = fmt(FcfPanel.tied(o));
			p.text(FcfPanel.fit(item(o), x + w - lx - w(val) - 6), lx, ly, INK);
			p.text(val, x + w - w(val), ly, INK);
			Runnable r2 = view(o);
			if (r2 != null) btn(lx, ly - 1, x + w, ly + 9, r2);
			ly += 11;
		}
	}

	/** Income-statement style: what your open positions are worth against what you paid / asked. */
	private static void biReport(Highlight.Painter p, int x, int y, int w, int h, JsonObject n, List<JsonObject> pos) {
		double bidCost = 0, winValue = 0, listAsk = 0, listValue = 0, listNet = 0, refunds = 0;
		int unvalued = 0;
		for (JsonObject o : pos) {
			if (sale(o)) {
				listAsk += d(o, "amount");
				listNet += d(o, "net");
				listValue += HudFormatter.num(o, "value") == null ? d(o, "amount") : Math.min(d(o, "amount"), d(o, "value"));
			} else if (winning(o)) {
				bidCost += d(o, "amount");
				if (HudFormatter.num(o, "expected_net") == null) unvalued++;
				winValue += d(o, "expected_net");
			} else refunds += d(o, "amount");
		}
		Object[][] lines = {
				{"INCOME STATEMENT (open positions)", null, true},
				{"Winning items, resale after fees", winValue, false},
				{"− Cost of winning bids", -bidCost, false},
				{"= Gross profit on purchases", winValue - bidCost, true},
				{"", null, false},
				{"Listings at asking price", listAsk, false},
				{"− Claim tax", listNet - listAsk, false},
				{"− Overpricing vs value", listValue - listAsk, false},
				{"= Realistic sales value", listValue - (listAsk - listNet), true},
				{"", null, false},
				{"Outbid coins to collect", refunds, false},
		};
		int ry = y;
		for (Object[] l : lines) {
			String lab = (String) l[0];
			Double v = (Double) l[1];
			boolean bold = (boolean) l[2];
			p.text(lab, x, ry, bold ? INK : MUTED);
			if (v != null) {
				String t = signedIf(v, lab.startsWith("−") || lab.startsWith("="));
				p.text(t, x + w - w(t), ry, bold ? (v >= 0 ? GREEN : RED) : INK);
			}
			if (bold && v != null) p.fill(x, ry - 2, x + w, ry - 1, LINE);
			ry += 12;
		}
		if (unvalued > 0) p.text(unvalued + " winning item(s) have no value estimate yet", x, ry + 4, MUTED);
	}

	private static String signedIf(double v, boolean sign) {
		return sign ? signed(v) : fmt(v);
	}

	// ---------------------------------------------------------------- coach
	private static List<String[]> coach(JsonObject n, List<JsonObject> pos) {
		List<String[]> m = new ArrayList<>();
		if (n == null) return m;
		for (JsonObject o : pos) {
			if (sale(o) && o.has("overpriced") && o.get("overpriced").getAsBoolean())
				m.add(new String[]{"warn", item(o) + " is listed above its value", "Listed " + fmt(d(o, "amount")) + ", worth about " + fmt(d(o, "value")) + ". It may not sell; consider relisting."});
			Double e = HudFormatter.num(o, "end");
			if (!sale(o) && winning(o) && e != null && e - System.currentTimeMillis() < 600_000L && e > System.currentTimeMillis())
				m.add(new String[]{"good", item(o) + " ends in " + ends(o), "You are winning at " + fmt(d(o, "amount")) + "."});
			Double pr = HudFormatter.num(o, "profit");
			if (!sale(o) && winning(o) && pr != null && pr < 0)
				m.add(new String[]{"bad", "Loss on " + item(o), "Your bid " + fmt(d(o, "amount")) + " is above its resale after fees (" + signed(pr) + ")."});
		}
		long outbid = pos.stream().filter(o -> "outbid".equals(str(o, "status"))).count();
		if (outbid > 0) m.add(new String[]{"warn", outbid + " bid(s) outbid", fmt(d(n, "refunds")) + " to collect. Open Purchases to bid again or collect."});
		Double wallet = walletNow(n);
		double tied = d(n, "sales") + d(n, "bids") + d(n, "refunds");
		if (wallet != null && tied > 0 && tied / (tied + wallet) > 0.6)
			m.add(new String[]{"warn", "Most of your coins are tied up", String.format(Locale.ROOT, "%.0f%% of your coins sit on the AH. Keep cash to catch deals.", 100 * tied / (tied + wallet))});
		long stale = (long) ((System.currentTimeMillis() - d(HudFormatter.obj(FcfPanel.report(), "fcf"), "updated")) / 60000);
		if (stale > 45) m.add(new String[]{"bad", "Data is " + stale + " min old", "Start run.py on your PC, or wait for the next 15-min AH scan."});
		int deals = Deals.list().size();
		if (deals > 0) m.add(new String[]{"good", deals + " underpriced BIN(s) found", "/skyprice deals lists them."});
		if (m.isEmpty()) m.add(new String[]{"good", "All clear", "No warnings right now."});
		return m;
	}

	private static void coachWin(Highlight.Painter p, int x, int y, int w, int h, JsonObject n, List<JsonObject> pos) {
		int ry = y;
		for (String[] c : coach(n, pos)) {
			if (ry > y + h - 22) break;
			int col = c[0].equals("bad") ? RED : c[0].equals("warn") ? 0xFFE8A317 : c[0].equals("good") ? GREEN : 0xFF6AA5DF;
			p.fill(x, ry, x + w, ry + 22, 0xFFF7F9FB);
			p.fill(x, ry, x + 3, ry + 22, col);
			p.text(FcfPanel.fit(c[1], w - 10), x + 7, ry + 2, INK);
			p.text(FcfPanel.fit(c[2], w - 10), x + 7, ry + 12, MUTED);
			ry += 26;
		}
	}

	private static void messages(Highlight.Painter p, int x, int y, int w, int h) {
		List<OutbidHud.Entry> list = OutbidHud.list();
		int ry = y;
		if (list.isEmpty()) p.text("No messages yet. Outbid notices from chat appear here.", x, ry, MUTED);
		for (OutbidHud.Entry e : list) {
			if (ry > y + h - 22) break;
			p.text("Message from Auction House", x, ry, INK);
			long mins = (System.currentTimeMillis() - e.at()) / 60000;
			String t = mins < 60 ? mins + " min ago" : (mins / 60) + " h ago";
			p.text(t, x + w - w(t), ry, MUTED);
			p.text(FcfPanel.fit("You were outbid by " + e.outbidBy() + " coins on " + e.item() + ". Click to open.", w), x, ry + 10, MUTED);
			p.fill(x, ry + 20, x + w, ry + 21, LINE);
			final String uuid = e.uuid();
			btn(x, ry - 1, x + w, ry + 20, () -> Chat.command("viewauction " + uuid));
			ry += 24;
		}
	}

	// ---------------------------------------------------------------- bazaar (stocks, candy, …)
	static List<String> bazaarList() {
		List<String> l = cfg.bazaarWatch == null ? new ArrayList<>() : new ArrayList<>(cfg.bazaarWatch);
		if (l.isEmpty()) l.addAll(List.of(DEFAULT_BAZAAR));
		return l;
	}

	private static void bazaar(Highlight.Painter p, int x, int y, int w, int h) {
		List<String> keys = bazaarList();
		if (bzSel == null || !keys.contains(bzSel)) bzSel = keys.get(0);
		int tableH = Math.min((int) (h * 0.62), 26 + keys.size() * 11);
		List<Row> rows = new ArrayList<>();
		for (String k : keys) {
			DataClient.Live live = data.live(k);
			DataClient.Doc doc = data.doc(k);
			JsonObject st = doc == null || doc.json() == null ? null : HudFormatter.obj(doc.json(), "stats", "24h", "sell");
			double buy = live == null ? Double.NaN : live.buy(), sell = live == null ? Double.NaN : live.sell();
			Double avg = HudFormatter.num(st, "avg"), hi = HudFormatter.num(st, "hi"), lo = HudFormatter.num(st, "lo");
			String spread = live == null || buy <= 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", (buy - sell) / buy * 100);
			boolean selRow = k.equals(bzSel);
			int chg = 0;
			if (avg != null && !Double.isNaN(sell)) chg = sell >= avg ? GREEN : RED;
			rows.add(new Row(new String[]{pretty(k), Double.isNaN(sell) ? "…" : fmt(sell), Double.isNaN(buy) ? "…" : fmt(buy),
					avg == null ? "-" : fmt(avg), hi == null ? "-" : fmt(hi), lo == null ? "-" : fmt(lo), spread},
					new int[]{selRow ? ORANGE : 0, chg, 0, 0, GREEN, RED, MUTED}, null, 0, () -> bzSel = k));
		}
		table(p, x, y, w, tableH, new Col[]{new Col("Product", 0.30, false), new Col("Sell now", 0.12, true), new Col("Buy now", 0.12, true),
				new Col("24h avg", 0.12, true), new Col("24h high", 0.12, true), new Col("24h low", 0.12, true), new Col("Spread", 0.10, true)},
				rows, null);
		bazaarChart(p, x, y + tableH + 4, w, h - tableH - 4, bzSel);
	}

	static String pretty(String k) {
		StringBuilder sb = new StringBuilder();
		for (String part : k.toLowerCase(Locale.ROOT).split("_")) {
			if (part.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return sb.toString();
	}

	/** 7-day hourly sell + buy price of one bazaar product (published history), plus the live price. */
	private static void bazaarChart(Highlight.Painter p, int x, int y, int w, int h, String key) {
		if (h < 40) return;
		DataClient.Doc doc = data.doc(key);
		p.text(pretty(key) + ": price, last 7 days", x, y, INK);
		p.fill(x + 150, y + 3, x + 166, y + 6, 0xFF2EA44F);
		p.text("Sell", x + 170, y, INK);
		p.fill(x + 196, y + 3, x + 212, y + 6, 0xFF1E74D6);
		p.text("Buy", x + 216, y, INK);
		List<long[]> sell = new ArrayList<>(), buy = new ArrayList<>();
		JsonObject j = doc == null ? null : doc.json();
		Double start = HudFormatter.num(j, "start"), step = HudFormatter.num(j, "step");
		JsonObject series = HudFormatter.obj(j, "series");
		if (start != null && step != null && series != null) {      // hourly history from the collector / website
			sell = pts(series, "sell", start, step);
			buy = pts(series, "buy", start, step);
		}
		JsonObject hist = HudFormatter.obj(data.bazaarHistory(), "items");
		if (hist != null && hist.has(key) && hist.get(key).isJsonArray()) {   // 15-min points from the GitHub job
			for (JsonElement e : hist.getAsJsonArray(key)) {
				JsonArray r = e.getAsJsonArray();
				long ts = r.get(0).getAsLong();
				buy.add(new long[]{ts, Math.round(r.get(1).getAsDouble())});
				sell.add(new long[]{ts, Math.round(r.get(2).getAsDouble())});
			}
			buy.sort((u, v) -> Long.compare(u[0], v[0]));
			sell.sort((u, v) -> Long.compare(u[0], v[0]));
		}
		DataClient.Live live = data.live(key);
		long now = System.currentTimeMillis();
		if (live != null) {
			sell.add(new long[]{now, Math.round(live.sell())});
			buy.add(new long[]{now, Math.round(live.buy())});
		}
		long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
		for (List<long[]> l : List.of(sell, buy)) for (long[] q : l) { lo = Math.min(lo, q[1]); hi = Math.max(hi, q[1]); }
		if (lo == Long.MAX_VALUE) { p.text("No prices yet.", x, y + 14, MUTED); return; }
		long pad = Math.max(1, (hi - lo) / 10);
		lo = Math.max(0, lo - pad);
		hi += pad;
		long from = now - 7 * 86_400_000L;
		int gx = x + 36, gy = y + 14, gw = w - 40, gh = h - 28;
		for (int k = 0; k <= 3; k++) {
			long v = lo + (hi - lo) * k / 3;
			int yy = gy + gh - (int) ((double) (v - lo) / (hi - lo) * gh);
			p.fill(gx, yy, gx + gw, yy + 1, 0xFFE6E6E6);
			String t = fmt((double) v);
			p.text(t, gx - 3 - w(t), yy - 4, MUTED);
		}
		p.text("7 days ago", gx, gy + gh + 3, MUTED);
		p.text("now", gx + gw - w("now"), gy + gh + 3, MUTED);
		FcfPanel.line(p, buy, from, now, lo, hi, gx, gy, gw, gh, 0xFF1E74D6);
		FcfPanel.line(p, sell, from, now, lo, hi, gx, gy, gw, gh, 0xFF2EA44F);
	}

	private static List<long[]> pts(JsonObject series, String k, double start, double step) {
		List<long[]> out = new ArrayList<>();
		if (!series.has(k) || !series.get(k).isJsonArray()) return out;
		JsonArray a = series.getAsJsonArray(k);
		for (int i = 0; i < a.size(); i++) {
			JsonElement e = a.get(i);
			if (e == null || e.isJsonNull()) continue;
			out.add(new long[]{(long) (start + i * step), Math.round(e.getAsDouble())});
		}
		return out;
	}
}
