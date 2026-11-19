package dev.notenoughflips;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Trade Desk: a simple ERP for your SkyBlock trading. The "DESK" button above the inventory (or /nef desk) turns the
 * inventory screen into a full-screen desk with six sections:
 *
 *   Home      key numbers, a to-do list (collect coins, outbid, overpriced listings, losses, tips) and what ends next
 *   Stock     everything you have on the AH, with filters (listed, winning, to collect)
 *   Buying    your bids: your bid, top bid, what the item is worth, profit, time left
 *   Selling   your listings: price, worth, what you get after tax, time left
 *   Finance   where your coins are and will be once everything settles, and the profit you made
 *   Market    bazaar prices (your watch list with 7-day charts) and bazaar tips (misprices, correlations)
 *
 * Clicks inside the desk never reach the inventory. Clicking a row opens that auction with /viewauction; you act yourself.
 */
final class Desk {
	// dark theme, same family as the FLIP panel
	private static final int BG = 0xF00B0F13, PANEL = 0xFF151B21, SIDE = 0xFF11161B, INK = 0xFFE4E8EC, MUTED = 0xFF8A949E,
			LINE = 0xFF27313A, ORANGE = 0xFFFFAA00, GREEN = 0xFF4CC46A, RED = 0xFFFF5F57, AMBER = 0xFFE8A317,
			BLUE = 0xFF5AA9F0, LILAC = 0xFFB59CF2, YELLOW = 0xFFE8C547, CHIP = 0xFF222B33, SEL = 0xFF3D3214, CARD = 0xFF1B232B;
	static final String[] DEFAULT_BAZAAR = {"STOCK_OF_STONKS", "ULTIMATE_CARROT_CANDY", "SUPERB_CARROT_CANDY", "GREAT_CARROT_CANDY",
			"SIMPLE_CARROT_CANDY", "PURPLE_CANDY", "GREEN_CANDY", "DARK_CANDY", "ROCK_CANDY"};

	private record Btn(int x1, int y1, int x2, int y2, Runnable action) {}

	/** One to-do on the Home page. level: 0 = problem, 1 = needs you, 2 = good news, 3 = tip. */
	private record Todo(int level, String title, String text, Runnable action) {}

	private static NefConfig cfg;
	private static DataClient data;
	private static boolean on;
	private static String win = "home";
	private static String stockFilter = "all";
	private static String marketView = "prices";
	private static int page;
	private static String bzSel;
	private static final List<Btn> buttons = new ArrayList<>();

	private Desk() {}

	static void init(NefConfig config, DataClient client) {
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

		int colW = Math.min(118, Math.max(92, W / 6));
		sidebar(p, 0, 0, colW, H, n, pos);
		int deskX = colW + 8, deskW = W - deskX - 6;
		window(p, deskX, 6, deskW, H - 12, n, pos);
	}

	/** Returns true when the Trade Desk is open (the click is used here and must not reach the inventory). */
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

	private static boolean outbid(JsonObject o) {
		return "outbid".equals(str(o, "status"));
	}

	private static boolean ended(JsonObject o) {
		return o.has("ended") && o.get("ended").getAsBoolean();
	}

	private static boolean overpriced(JsonObject o) {
		return sale(o) && o.has("overpriced") && o.get("overpriced").getAsBoolean();
	}

	private static boolean toCollect(JsonObject o) {
		return ended(o) || outbid(o);
	}

	/** Coins (or item value) waiting to be claimed for one position. */
	private static double collectAmount(JsonObject o) {
		return outbid(o) ? d(o, "amount") : sale(o) ? d(o, "net") : d(o, "value");
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

	private static double onAh(JsonObject n) {
		return n == null ? 0 : d(n, "sales") + d(n, "bids") + d(n, "refunds");
	}

	private static Runnable view(JsonObject o) {
		String uuid = str(o, "uuid");
		return uuid == null ? null : () -> Cmd.viewAuction(uuid);
	}

	// ================================================================== sidebar (sections)
	/** id, name, one-line description shown next to the title. */
	private static final String[][] SECTIONS = {
			{"home", "Home", "what needs you right now"},
			{"stock", "Stock", "everything you have on the auction house"},
			{"buying", "Buying", "your bids"},
			{"selling", "Selling", "your listings"},
			{"finance", "Finance", "your coins and your profit"},
			{"market", "Market", "bazaar prices and tips"}};

	private static void sidebar(Highlight.Painter p, int x, int y, int w, int H, JsonObject n, List<JsonObject> pos) {
		p.fill(x, y, x + w, y + H, SIDE);
		p.fill(x + w - 1, y, x + w, y + H, LINE);
		int ix = x + 8, ly = y + 8;
		p.text("Trade Desk", ix, ly, ORANGE);
		p.text("✕", x + w - 14, ly, MUTED);
		btn(x + w - 18, ly - 3, x + w - 2, ly + 10, () -> on = false);
		ly += 20;
		for (String[] sct : SECTIONS) {
			boolean sel = win.equals(sct[0]);
			if (sel) {
				p.fill(x, ly - 3, x + w - 1, ly + 12, SEL);
				p.fill(x, ly - 3, x + 2, ly + 12, ORANGE);
			}
			p.text(sct[1], ix, ly + 1, sel ? ORANGE : INK);
			int badge = badge(sct[0], n, pos);
			if (badge > 0) {
				String b = String.valueOf(badge);
				int bc = sct[0].equals("market") ? BLUE : RED;
				p.fill(x + w - 14 - w(b), ly, x + w - 8, ly + 10, bc);
				p.text(b, x + w - 11 - w(b), ly + 1, 0xFFFFFFFF);
			}
			final String id = sct[0];
			btn(x, ly - 3, x + w - 1, ly + 12, () -> open(id));
			ly += 17;
		}
		// footer: cash + data age
		Double wallet = walletNow(n);
		int fy = H - 34;
		p.fill(ix, fy - 4, x + w - 8, fy - 3, LINE);
		p.text("Cash", ix, fy, MUTED);
		String cash = wallet == null ? "?" : fmt(wallet);
		p.text(cash, x + w - 8 - w(cash), fy, INK);
		long mins = FcfPanel.fetchedAt() == 0 ? -1 : (long) ((System.currentTimeMillis() - d(HudFormatter.obj(FcfPanel.report(), "fcf"), "updated")) / 60000);
		p.text(FcfPanel.fit(mins < 0 ? "no AH scan yet" : "updated " + (mins < 1 ? "just now" : mins + " min ago"), w - 16), ix, fy + 12, MUTED);
	}

	/** Red number = things that need you (Market: blue = strong tips). */
	private static int badge(String id, JsonObject n, List<JsonObject> pos) {
		return switch (id) {
			case "home" -> (int) todos(n, pos).stream().filter(t -> t.level() <= 1).count();
			case "buying" -> (int) pos.stream().filter(o -> !sale(o) && outbid(o)).count();
			case "selling" -> (int) pos.stream().filter(Desk::overpriced).count();
			case "market" -> strongSignals();
			default -> 0;
		};
	}

	// ================================================================== window frame
	private static void window(Highlight.Painter p, int x, int y, int w, int h, JsonObject n, List<JsonObject> pos) {
		String title = win, sub = "";
		for (String[] t : SECTIONS) if (t[0].equals(win)) { title = t[1]; sub = t[2]; }
		p.fill(x, y, x + w, y + h, PANEL);
		p.fill(x, y, x + w, y + 1, LINE);
		p.fill(x, y + h - 1, x + w, y + h, LINE);
		p.text(title, x + 6, y + 5, INK);
		p.fill(x + 6, y + 15, x + 6 + w(title), y + 16, ORANGE);
		p.text(FcfPanel.fit(sub, w - w(title) - 20), x + 12 + w(title), y + 5, MUTED);
		int bx = x + 6, by = y + 21, bw = w - 12, bh = h - 25;
		boolean needsAh = !win.equals("market") && !win.equals("home");
		if (needsAh && n == null) {
			p.text(FcfPanel.report() == null ? "Loading your auctions…" : "No auctions found yet. Your auctions are checked every 15 min.", bx, by + 4, MUTED);
			p.text("Tip: open the FLIP panel (press E) to find something to buy or sell.", bx, by + 16, MUTED);
			return;
		}
		switch (win) {
			case "stock" -> stock(p, bx, by, bw, bh, pos, n);
			case "buying" -> buying(p, bx, by, bw, bh, pos);
			case "selling" -> selling(p, bx, by, bw, bh, pos);
			case "finance" -> finance(p, bx, by, bw, bh, n);
			case "market" -> market(p, bx, by, bw, bh);
			default -> home(p, bx, by, bw, bh, n, pos);
		}
	}

	// ================================================================== table helper
	/** cols: header text; width share (fractions of the table width); right-aligned or not. */
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
			p.text(hd, tx, y, MUTED);
		}
		p.fill(x, y + 10, x + w, y + 11, LINE);
		int rowH = 12, avail = h - 14 - (foot != null ? 12 : 0) - 10;
		int per = Math.max(1, avail / rowH), pages = Math.max(1, (rows.size() + per - 1) / per);
		page = Math.min(page, pages - 1);
		int ry = y + 14;
		for (int r = page * per; r < Math.min(rows.size(), (page + 1) * per); r++) {
			Row row = rows.get(r);
			if (r % 2 == 1) p.fill(x, ry - 2, x + w, ry + 10, 0xFF182027);
			for (int i = 0; i < cols.length; i++) {
				String c = row.cells()[i] == null ? "" : row.cells()[i];
				int colW = cx[i + 1] - cx[i] - 5;
				int tx = cx[i] + 2;
				if (i == 0 && row.badge() != null) {
					p.fill(tx, ry - 1, tx + 9, ry + 8, row.badgeColor());
					p.text(row.badge(), tx + 2, ry, 0xFFFFFFFF);
					tx += 12;
					colW -= 12;
				}
				c = FcfPanel.fit(c, colW);
				if (cols[i].right()) tx = cx[i + 1] - 3 - w(c);
				p.text(c, tx, ry, row.colors() == null || row.colors()[i] == 0 ? INK : row.colors()[i]);
			}
			if (row.onClick() != null) btn(x, ry - 2, x + w, ry + 10, row.onClick());
			ry += rowH;
		}
		if (rows.isEmpty()) p.text("Nothing here right now.", x + 2, ry + 2, MUTED);
		int fy = y + h - 10;
		if (foot != null) p.text(FcfPanel.fit(foot, w - 50), x, fy, MUTED);
		if (pages > 1) {
			String pg = "◀ " + (page + 1) + "/" + pages + " ▶";
			int px = x + w - w(pg);
			p.text(pg, px, fy, BLUE);
			btn(px - 2, fy - 2, px + 8, fy + 9, () -> page = Math.max(0, page - 1));
			btn(px + w(pg) - 10, fy - 2, px + w(pg) + 2, fy + 9, () -> page++);
		}
	}

	private static int chip(Highlight.Painter p, int x, int y, String label, boolean sel, Runnable r) {
		int cw = w(label) + 8;
		p.fill(x, y - 2, x + cw, y + 9, sel ? SEL : CHIP);
		p.text(label, x + 4, y, sel ? ORANGE : INK);
		btn(x, y - 2, x + cw, y + 9, r);
		return x + cw + 3;
	}

	// ================================================================== Home
	private static void home(Highlight.Painter p, int x, int y, int w, int h, JsonObject n, List<JsonObject> pos) {
		// key numbers as tiles; click a tile to open the matching section
		Double wallet = walletNow(n);
		double collect = 0;
		for (JsonObject o : pos) if (toCollect(o)) collect += collectAmount(o);
		double today = ProfitLog.summary(ProfitLog.startOfDay(0)).profit();
		Object[][] tiles = {
				{"Cash", wallet == null ? "?" : fmt(wallet), INK, "finance"},
				{"On the AH", fmt(onAh(n)), INK, "stock"},
				{"To collect", fmt(collect), collect > 0 ? GREEN : INK, "stock"},
				{"Profit today", signed(today), today >= 0 ? GREEN : RED, "finance"}};
		int gap = 4, tw = (w - gap * 3) / 4, th = 26;
		for (int i = 0; i < tiles.length; i++) {
			int tx = x + i * (tw + gap);
			p.fill(tx, y, tx + tw, y + th, CARD);
			p.text((String) tiles[i][0], tx + 5, y + 4, MUTED);
			p.text(FcfPanel.fit((String) tiles[i][1], tw - 10), tx + 5, y + 15, (int) tiles[i][2]);
			final String to = (String) tiles[i][3];
			final boolean collectTile = tiles[i][0].equals("To collect");
			btn(tx, y, tx + tw, y + th, () -> { open(to); if (collectTile) stockFilter = "collect"; });
		}
		int ty = y + th + 8;
		int leftW = (int) (w * 0.60), rx = x + leftW + 10, rw = w - leftW - 10;

		// to-do list
		List<Todo> todos = todos(n, pos);
		long needs = todos.stream().filter(t -> t.level() <= 1).count();
		p.text("To do", x, ty, INK);
		p.text(needs == 0 ? "nothing urgent" : needs + " need" + (needs == 1 ? "s" : "") + " you", x + w("To do") + 6, ty, needs == 0 ? GREEN : AMBER);
		int cy = ty + 12, shown = 0;
		for (Todo t : todos) {
			if (cy > y + h - 24) break;
			int col = t.level() == 0 ? RED : t.level() == 1 ? AMBER : t.level() == 2 ? GREEN : BLUE;
			p.fill(x, cy, x + leftW, cy + 21, CARD);
			p.fill(x, cy, x + 3, cy + 21, col);
			p.text(FcfPanel.fit(t.title(), leftW - 20), x + 7, cy + 2, INK);
			p.text(FcfPanel.fit(t.text(), leftW - 20), x + 7, cy + 11, MUTED);
			if (t.action() != null) {
				p.text("›", x + leftW - 8, cy + 6, MUTED);
				btn(x, cy, x + leftW, cy + 21, t.action());
			}
			cy += 24;
			shown++;
		}
		if (shown < todos.size()) p.text("+" + (todos.size() - shown) + " more", x, cy, MUTED);

		// what ends next
		p.text("Ending next", rx, ty, INK);
		List<JsonObject> sorted = new ArrayList<>();
		for (JsonObject o : pos) if (!ended(o) && HudFormatter.num(o, "end") != null) sorted.add(o);
		sorted.sort((a, b) -> Double.compare(d(a, "end"), d(b, "end")));
		int ey = ty + 12;
		if (sorted.isEmpty()) p.text(FcfPanel.fit(n == null ? "Waiting for your auctions…" : "Nothing running.", rw), rx, ey, MUTED);
		DateTimeFormatter hm = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH);
		for (JsonObject o : sorted) {
			if (ey > y + h - 20) break;
			Double e = HudFormatter.num(o, "end");
			boolean soon = e - System.currentTimeMillis() < 3_600_000L;
			String left = ends(o), when = Instant.ofEpochMilli(e.longValue()).atZone(ZoneId.systemDefault()).format(hm);
			String what = sale(o) ? "you sell" : winning(o) ? "you win" : "outbid";
			p.text(left, rx, ey, soon ? RED : INK);
			p.text(FcfPanel.fit(item(o), rw - 44), rx + 40, ey, INK);
			p.text(FcfPanel.fit(when + " · " + what + " · " + fmt(d(o, "amount")), rw - 44), rx + 40, ey + 9, MUTED);
			Runnable v = view(o);
			if (v != null) btn(rx, ey - 1, rx + rw, ey + 18, v);
			ey += 21;
		}
		p.text("Click anything to open it.", x, y + h - 9, MUTED);
	}

	/** Everything that wants attention, most urgent first. Each one opens the auction or section it is about. */
	private static List<Todo> todos(JsonObject n, List<JsonObject> pos) {
		List<Todo> m = new ArrayList<>();
		if (n != null) {
			long stale = (long) ((System.currentTimeMillis() - d(HudFormatter.obj(FcfPanel.report(), "fcf"), "updated")) / 60000);
			if (stale > 45) m.add(new Todo(0, "Auction data is " + stale + " min old", "It refreshes every 15 min, or press ⟳ in the FLIP panel.", null));
		}
		Set<String> known = new HashSet<>();
		for (JsonObject o : pos) {
			String uuid = str(o, "uuid");
			if (uuid != null) known.add(uuid);
			Double pr = HudFormatter.num(o, "profit");
			if (!sale(o) && winning(o) && pr != null && pr < 0)
				m.add(new Todo(0, "Loss on " + item(o), "Your bid " + fmt(d(o, "amount")) + " is more than it resells for (" + signed(pr) + ").", view(o)));
			if (!sale(o) && outbid(o))
				m.add(new Todo(1, "Outbid on " + item(o), "Collect your " + fmt(d(o, "amount")) + " back, or bid again.", view(o)));
			else if (ended(o))
				m.add(new Todo(2, sale(o) ? "Sold: " + item(o) : "Won: " + item(o),
						sale(o) ? "Collect " + fmt(d(o, "net")) + " coins." : "Claim your item.", view(o)));
			if (overpriced(o))
				m.add(new Todo(1, item(o) + " is priced above its value", "Listed " + fmt(d(o, "amount")) + ", worth about " + fmt(d(o, "value")) + ". It may not sell.", view(o)));
			Double e = HudFormatter.num(o, "end");
			long now = System.currentTimeMillis();
			if (!sale(o) && winning(o) && e != null && e > now && e - now < 600_000L)
				m.add(new Todo(2, item(o) + " ends in " + ends(o), "You are winning at " + fmt(d(o, "amount")) + ".", view(o)));
		}
		for (OutbidHud.Entry e : OutbidHud.list()) {                   // outbid notices from chat that the last scan hasn't seen
			if (e.uuid() != null && known.contains(e.uuid())) continue;
			m.add(new Todo(1, "Outbid on " + e.item(), "Someone bid " + e.outbidBy() + " coins more.", e.uuid() == null ? null : () -> Cmd.viewAuction(e.uuid())));
		}
		Double wallet = walletNow(n);
		double tied = onAh(n);
		if (wallet != null && tied > 0 && tied / (tied + wallet) > 0.6)
			m.add(new Todo(1, "Most of your coins are tied up", String.format(Locale.ROOT, "%.0f%% is on the AH. Keep some cash for deals.", 100 * tied / (tied + wallet)),
					() -> open("finance")));
		int tips = 0;
		for (JsonObject sg : signalList("all")) {                       // the strongest bazaar tips
			if (tips >= 2 || !"high".equals(str(sg, "conf"))) continue;
			final String id = str(sg, "id");
			m.add(new Todo(3, "Bazaar tip: " + str(sg, "title"), str(sg, "text"), () -> { open("market"); marketView = "tips"; sigSelId = id; }));
			tips++;
		}
		int deals = Deals.list().size();
		if (deals > 0) m.add(new Todo(3, deals + " cheap BIN(s) found", "/nef deals lists them.", null));
		m.sort((a, b) -> Integer.compare(a.level(), b.level()));
		if (m.isEmpty()) m.add(new Todo(2, "All clear", "Nothing needs you right now.", null));
		return m;
	}

	// ================================================================== Stock
	private static void stock(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos, JsonObject n) {
		int cx = x;
		for (String[] f : new String[][]{{"all", "All"}, {"listed", "Listed"}, {"winning", "Winning"}, {"collect", "To collect"}}) {
			final String id = f[0];
			cx = chip(p, cx, y, f[1], stockFilter.equals(id), () -> { stockFilter = id; page = 0; });
		}
		List<Row> rows = new ArrayList<>();
		for (JsonObject o : pos) {
			boolean s = sale(o), win = winning(o), col = toCollect(o);
			boolean keep = switch (stockFilter) {
				case "listed" -> s && !ended(o);
				case "winning" -> !s && win;
				case "collect" -> col;
				default -> true;
			};
			if (!keep) continue;
			Double val = HudFormatter.num(o, "value");
			String status = ended(o) ? (s ? "sold · collect" : "won · claim") : s ? "listed" : win ? "winning" : "outbid · collect";
			int sc = col ? GREEN : win ? YELLOW : 0;
			rows.add(new Row(new String[]{item(o), String.valueOf((int) d(o, "count")), fmt(d(o, "amount")), val == null ? "-" : fmt(val), status, ends(o)},
					new int[]{0, 0, 0, 0, sc, 0}, s ? "S" : "B", s ? 0xFFF26A1B : 0xFF3D8BD9, view(o)));
		}
		table(p, x, y + 14, w, h - 14, new Col[]{new Col("Item", 0.36, false), new Col("Qty", 0.07, true), new Col("Price", 0.13, true),
				new Col("Worth", 0.13, true), new Col("Status", 0.19, false), new Col("Ends", 0.12, true)}, rows,
				"S = selling · B = buying · on the AH: " + fmt(onAh(n)));
	}

	// ================================================================== Buying
	private static void buying(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos) {
		List<Row> rows = new ArrayList<>();
		double total = 0;
		for (JsonObject o : pos) {
			if (sale(o)) continue;
			boolean win = winning(o);
			Double pr = HudFormatter.num(o, "profit");
			if (win) total += d(o, "amount");
			rows.add(new Row(new String[]{item(o), fmt(d(o, "amount")), fmt(d(o, "top_bid")), HudFormatter.num(o, "value") == null ? "-" : fmt(d(o, "value")),
					pr == null ? (win ? "?" : "-") : signed(pr), win ? "winning" : "outbid", ends(o)},
					new int[]{0, 0, 0, 0, pr == null ? MUTED : pr >= 0 ? GREEN : RED, win ? GREEN : AMBER, 0}, null, 0, view(o)));
		}
		table(p, x, y, w, h, new Col[]{new Col("Item", 0.30, false), new Col("Your bid", 0.12, true), new Col("Top bid", 0.12, true),
				new Col("Worth", 0.12, true), new Col("Profit", 0.12, true), new Col("Status", 0.11, false), new Col("Ends", 0.11, true)}, rows,
				"Winning bids: " + fmt(total) + " · Profit = what it resells for after tax, minus your bid");
	}

	// ================================================================== Selling
	private static void selling(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos) {
		List<Row> rows = new ArrayList<>();
		double total = 0;
		for (JsonObject o : pos) {
			if (!sale(o)) continue;
			boolean over = overpriced(o);
			total += d(o, "net");
			boolean bin = o.has("bin") && o.get("bin").getAsBoolean();
			rows.add(new Row(new String[]{item(o), fmt(d(o, "amount")), HudFormatter.num(o, "value") == null ? "-" : fmt(d(o, "value")),
					fmt(d(o, "net")), ended(o) ? "sold · collect" : over ? "too expensive" : bin ? "BIN" : "auction", ends(o)},
					new int[]{0, 0, 0, 0, ended(o) ? GREEN : over ? RED : MUTED, 0}, null, 0, view(o)));
		}
		table(p, x, y, w, h, new Col[]{new Col("Item", 0.34, false), new Col("Price", 0.13, true), new Col("Worth", 0.13, true),
				new Col("You get", 0.14, true), new Col("Status", 0.15, false), new Col("Ends", 0.11, true)}, rows,
				"You get " + fmt(total) + " if everything sells · \"You get\" is after the AH tax");
	}

	// ================================================================== Finance
	private static void finance(Highlight.Painter p, int x, int y, int w, int h, JsonObject n) {
		p.text("Your coins once everything settles", x, y, INK);
		Double wallet = walletNow(n);
		boolean subtract = n.has("bids_subtracted") && n.get("bids_subtracted").getAsBoolean();
		double w0 = wallet == null ? 0 : wallet, sales = d(n, "sales"), bids = d(n, "bids"), exp = d(n, "expected"), ref = subtract ? 0 : d(n, "refunds");
		double total = w0 + sales - (subtract ? bids : 0) + exp + ref, cons = total - sales + d(n, "sales_conservative");
		Object[][] steps = {{"Cash (purse + bank)", w0, 0}, {"+ Your listings sell", sales, 1}, {"− Your winning bids" + (subtract ? "" : " (already paid)"), -bids, subtract ? 2 : 3},
				{"+ Items you win, resold", exp, 1}, {"+ Outbid coins back", ref, 1}, {"= Total", total, 0}};
		double max = Math.max(1, Math.max(total, w0 + sales + exp + ref));
		int labW = Math.min(130, w / 3), barX = x + labW + 6, barW = w - labW - 60;
		int ry = y + 14;
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
			ry += 13;
		}
		p.text(FcfPanel.fit("If your listings only sell at what they are worth: " + fmt(cons), w), x, ry + 2, MUTED);
		ry += 22;

		// realised profit
		p.text("Profit you made", x, ry, INK);
		ry += 13;
		Object[][] periods = {{"Today", 0}, {"Last 7 days", 6}, {"Last 30 days", 29}};
		int colW = w / 3;
		for (int i = 0; i < periods.length; i++) {
			ProfitLog.Summary s = ProfitLog.summary(ProfitLog.startOfDay((int) periods[i][1]));
			int tx = x + i * colW;
			p.fill(tx, ry, tx + colW - 4, ry + 26, CARD);
			p.text((String) periods[i][0], tx + 5, ry + 4, MUTED);
			p.text(signed(s.profit()) + "  (" + s.flips() + " flip" + (s.flips() == 1 ? "" : "s") + ")", tx + 5, ry + 15, s.profit() >= 0 ? GREEN : RED);
		}
		ry += 32;
		p.text(FcfPanel.fit("Profit counts sales you made after buying the same item, after tax. /nef profit shows details.", w), x, ry, MUTED);
	}

	// ================================================================== Market
	private static void market(Highlight.Painter p, int x, int y, int w, int h) {
		int strong = strongSignals();
		int cx = chip(p, x, y, "Prices", marketView.equals("prices"), () -> { marketView = "prices"; page = 0; });
		chip(p, cx, y, strong > 0 ? "Tips (" + strong + ")" : "Tips", marketView.equals("tips"), () -> { marketView = "tips"; page = 0; });
		if (marketView.equals("tips")) signals(p, x, y + 15, w, h - 15);
		else bazaar(p, x, y + 15, w, h - 15);
	}

	// ---------------------------------------------------------------- bazaar signals (misprices, correlations)
	private static String sigView = "suggest", sigFilter = "all", sigSelId;

	private static List<JsonObject> signalList(String filter) {
		List<JsonObject> out = new ArrayList<>();
		JsonObject sj = data == null ? null : data.bzSignals();
		if (sj == null || !sj.has("signals")) return out;
		for (JsonElement e : sj.getAsJsonArray("signals")) {
			JsonObject o = e.getAsJsonObject();
			String a = str(o, "action");
			boolean keep = switch (filter) {
				case "buy" -> "buy".equals(a) || "prefer".equals(a);
				case "sell" -> "sell".equals(a) || "avoid".equals(a);
				case "craft" -> "craft".equals(a) || "premium".equals(str(o, "type"));
				default -> true;
			};
			if (keep) out.add(o);
		}
		return out;
	}

	private static int strongSignals() {
		return (int) signalList("all").stream().filter(o -> "high".equals(str(o, "conf"))).count();
	}

	private static final String[][] SIG_TYPES = {
			{"cheap", "B"}, {"rich", "S"}, {"pair", "P"}, {"follow", "F"}, {"craft", "C"}, {"premium", "$"}, {"event", "!"}};

	private static String letter(String type) {
		for (String[] t : SIG_TYPES) if (t[0].equals(type)) return t[1];
		return "?";
	}

	private static int typeColor(String type) {
		return switch (type == null ? "" : type) {
			case "cheap" -> GREEN;
			case "rich" -> RED;
			case "pair" -> BLUE;
			case "follow" -> LILAC;
			case "craft" -> ORANGE;
			case "event" -> 0xFF9B2335;
			default -> 0xFFB8960C;
		};
	}

	private static int confColor(String c) {
		return "high".equals(c) ? GREEN : "medium".equals(c) ? 0xFFB8960C : MUTED;
	}

	private static void signals(Highlight.Painter p, int x, int y, int w, int h) {
		JsonObject sj = data.bzSignals();
		int cx = chip(p, x, y, "Suggestions", sigView.equals("suggest"), () -> { sigView = "suggest"; page = 0; });
		cx = chip(p, cx, y, "Correlations", sigView.equals("corr"), () -> { sigView = "corr"; page = 0; });
		cx += 10;
		if (sigView.equals("suggest")) {
			for (String[] f : new String[][]{{"all", "All"}, {"buy", "Buy"}, {"sell", "Sell"}, {"craft", "Craft"}}) {
				final String id = f[0];
				cx = chip(p, cx, y, f[1], sigFilter.equals(id), () -> { sigFilter = id; page = 0; });
			}
		}
		if (sj == null) {
			p.text("Loading bazaar signals… (published every 2 h)", x, y + 18, MUTED);
			return;
		}
		long age = (System.currentTimeMillis() - (long) d(sj, "generated")) / 60000;
		String src = (age < 120 ? age + "m" : (age / 60) + "h") + " ago · " + (int) d(sj, "products") + " products · "
				+ (int) d(sj, "pairs") + " pairs";
		p.text(src, x + w - w(src), y, MUTED);
		int ty = y + 14;
		if (sigView.equals("corr")) {
			correlations(p, x, ty, w, h - 14, sj);
			return;
		}
		List<JsonObject> list = signalList(sigFilter);
		JsonObject sel = null;
		for (JsonObject o : list) if (str(o, "id").equals(sigSelId)) sel = o;
		if (sel == null && !list.isEmpty()) {
			sel = list.get(0);
			sigSelId = str(sel, "id");
		}
		int tableH = Math.max(60, (int) (h * 0.48));
		List<Row> rows = new ArrayList<>();
		for (JsonObject o : list) {
			String id = str(o, "id"), conf = str(o, "conf"), type = str(o, "type");
			double edge = d(o, "edge");
			boolean isSel = id.equals(sigSelId);
			rows.add(new Row(new String[]{str(o, "name"), str(o, "action"), fmt(d(o, "price")), fmt(d(o, "target")),
					String.format(Locale.ROOT, "%+.1f%%", edge * 100), conf},
					new int[]{isSel ? ORANGE : 0, 0, 0, 0, edge >= 0 ? GREEN : RED, confColor(conf)},
					letter(type), typeColor(type), () -> sigSelId = id));
		}
		table(p, x, ty, w, tableH, new Col[]{new Col("Product", 0.36, false), new Col("Do", 0.10, false), new Col("Now", 0.13, true),
				new Col("Target", 0.13, true), new Col("Edge", 0.13, true), new Col("Conf.", 0.15, true)}, rows,
				"B cheap · S rich · P pair · F follows · C craft · $ cheaper form · ! market event");
		if (sel != null) signalDetail(p, x, ty + tableH + 4, w, h - tableH - 18, sel, sj);
	}

	/** Explanation, purse-sized quantity, buttons and a 7-day chart (both products of a pair, indexed to the start). */
	private static void signalDetail(Highlight.Painter p, int x, int y, int w, int h, JsonObject o, JsonObject sj) {
		p.fill(x, y - 2, x + w, y - 1, LINE);
		String id = str(o, "id"), name = str(o, "name"), other = str(o, "other"), action = str(o, "action");
		p.text(FcfPanel.fit(str(o, "title"), w), x, y + 1, INK);
		p.text(FcfPanel.fit(str(o, "text"), w), x, y + 11, typeColor(str(o, "type")));
		int ly = y + 22;
		if (o.has("why")) for (JsonElement e : o.getAsJsonArray("why")) {
			p.text(FcfPanel.fit("• " + e.getAsString(), w), x, ly, MUTED);
			ly += 10;
		}
		double price = d(o, "price"), edge = d(o, "edge");
		double budget = FlipPanel.budget();
		double perRound = d(o, "units_h") * cfg.bazaarSharePercent / 100.0 * Math.max(0.25, cfg.bazaarRoundHours);
		long qty = (long) Math.max(0, Math.min(Math.min(perRound, price > 0 ? budget / price : 0), Market.MAX_ORDER));
		String size;
		if ("event".equals(str(o, "type"))) size = "Market event: these prices just changed for a reason. Flip lists ignore the members until it settles.";
		else if ("sell".equals(action)) size = "For what you hold: about " + fmt(price * edge) + " more per unit than its usual price.";
		else if ("prefer".equals(action)) size = "Use this one when you need the item; no flip margin after tax.";
		else size = qty < 1 ? "Your flip budget is below one unit." : String.format(Locale.ROOT, "Size: %,d units (%s of your %s flip budget) → ≈ +%s if it gets back to target",
				qty, fmt(qty * price), fmt(budget), fmt(qty * price * edge));
		p.text(FcfPanel.fit(size, w), x, ly + 1, INK);
		ly += 13;
		int bx = x;
		String b1 = "Open " + name + " in Bazaar";
		p.fill(bx, ly - 2, bx + w(b1) + 8, ly + 9, ORANGE);
		p.text(b1, bx + 4, ly, 0xFFFFFFFF);
		btn(bx, ly - 2, bx + w(b1) + 8, ly + 9, () -> { on = false; FlipPanel.pick(id, name); });
		bx += w(b1) + 12;
		boolean watched = bazaarList().contains(id);
		String b2 = watched ? "✔ Watching" : "+ Watch";
		p.fill(bx, ly - 2, bx + w(b2) + 8, ly + 9, CHIP);
		p.text(b2, bx + 4, ly, INK);
		btn(bx, ly - 2, bx + w(b2) + 8, ly + 9, () -> {
			if (!bazaarList().contains(id)) {
				List<String> l = bazaarList();
				l.add(id);
				cfg.bazaarWatch = l;
				cfg.save();
			}
		});
		bx += w(b2) + 12;
		if (other != null) {
			String on2 = HudFormatter.str(HudFormatter.obj(sj, "names"), other);
			String b3 = "Open " + (on2 == null ? pretty(other) : on2);
			p.fill(bx, ly - 2, bx + w(b3) + 8, ly + 9, CHIP);
			p.text(b3, bx + 4, ly, INK);
			final String oname = on2 == null ? pretty(other) : on2;
			btn(bx, ly - 2, bx + w(b3) + 8, ly + 9, () -> { on = false; FlipPanel.pick(other, oname); });
		}
		ly += 14;
		sparkChart(p, x, ly, w, y + h - ly, sj, id, other);
	}

	private static void sparkChart(Highlight.Painter p, int x, int y, int w, int h, JsonObject sj, String a, String b) {
		if (h < 34) return;
		JsonObject sp = HudFormatter.obj(sj, "spark");
		List<long[]> la = spark(sp, a), lb = b == null ? List.of() : spark(sp, b);
		if (la.isEmpty()) return;
		long from = Long.MAX_VALUE, to = Long.MIN_VALUE, lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
		for (List<long[]> l : List.of(la, lb)) for (long[] q : l) {
			from = Math.min(from, q[0]); to = Math.max(to, q[0]); lo = Math.min(lo, q[1]); hi = Math.max(hi, q[1]);
		}
		long pad = Math.max(1, (hi - lo) / 10);
		lo -= pad;
		hi += pad;
		int gx = x + 34, gy = y + 10, gw = w - 38, gh = h - 20;
		p.fill(gx, gy + gh - (int) ((10000.0 - lo) / (hi - lo) * gh), gx + gw, gy + gh - (int) ((10000.0 - lo) / (hi - lo) * gh) + 1, 0xFF3A444E);
		for (int k = 0; k <= 2; k++) {
			long v = lo + (hi - lo) * k / 2;
			int yy = gy + gh - (int) ((double) (v - lo) / (hi - lo) * gh);
			String t = String.format(Locale.ROOT, "%+.0f%%", (v / 10000.0 - 1) * 100);
			p.text(t, gx - 3 - w(t), yy - 4, MUTED);
		}
		JsonObject names = HudFormatter.obj(sj, "names");
		String na = HudFormatter.str(names, a), nb = b == null ? null : HudFormatter.str(names, b);
		p.fill(x, y + 3, x + 12, y + 5, ORANGE);
		p.text((na == null ? pretty(a) : na) + " (price vs 7 days ago)", x + 15, y, INK);
		if (nb != null || b != null) {
			int lx = x + 20 + w((na == null ? pretty(a) : na) + " (price vs 7 days ago)") + 8;
			p.fill(lx, y + 3, lx + 12, y + 5, BLUE);
			p.text(nb == null ? pretty(b) : nb, lx + 15, y, INK);
		}
		FcfPanel.line(p, lb, from, to, lo, hi, gx, gy, gw, gh, BLUE);
		FcfPanel.line(p, la, from, to, lo, hi, gx, gy, gw, gh, ORANGE);
	}

	/** Spark series indexed to 10 000 at its first value (so two products share one % axis). */
	private static List<long[]> spark(JsonObject sp, String id) {
		List<long[]> out = new ArrayList<>();
		if (sp == null || id == null || !sp.has(id) || !sp.get(id).isJsonArray()) return out;
		JsonArray a = sp.getAsJsonArray(id);
		if (a.size() < 3) return out;
		long t0 = a.get(0).getAsLong(), step = a.get(1).getAsLong();
		double first = Double.NaN;
		for (int i = 2; i < a.size(); i++) {
			if (a.get(i).isJsonNull()) continue;
			double v = a.get(i).getAsDouble();
			if (Double.isNaN(first)) first = v;
			if (first > 0) out.add(new long[]{t0 + (i - 2) * step, Math.round(v / first * 10000)});
		}
		return out;
	}

	private static void correlations(Highlight.Painter p, int x, int y, int w, int h, JsonObject sj) {
		JsonObject names = HudFormatter.obj(sj, "names");
		List<Row> rows = new ArrayList<>();
		if (sj.has("correlations")) for (JsonElement e : sj.getAsJsonArray("correlations")) {
			JsonObject c = e.getAsJsonObject();
			String a = str(c, "a"), b = str(c, "b");
			String na = names != null && names.has(a) ? names.get(a).getAsString() : pretty(a);
			String nb = names != null && names.has(b) ? names.get(b).getAsString() : pretty(b);
			boolean lag = d(c, "lag") > 0;
			double hrs = d(sj, "step_h");
			rows.add(new Row(new String[]{na, nb, String.format(Locale.ROOT, "%.2f", d(c, "rho")),
					lag ? nb + " follows ~" + Market.dur(hrs) + " later" : "move together", String.valueOf((int) d(c, "n"))},
					new int[]{0, 0, d(c, "rho") >= 0.8 ? GREEN : 0, lag ? LILAC : MUTED, MUTED}, lag ? "F" : "≈", lag ? LILAC : BLUE,
					() -> { sigView = "suggest"; sigFilter = "all"; sigSelId = a; page = 0; }));
		}
		table(p, x, y, w, h, new Col[]{new Col("Product", 0.28, false), new Col("With", 0.28, false), new Col("ρ", 0.08, true),
				new Col("How", 0.26, false), new Col("Bars", 0.10, true)}, rows,
				"ρ = correlation of price changes (1 = always together). Correlation ≠ cause: use it as a lead.");
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
		if (start != null && step != null && series != null) {      // hourly history from the data site
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
			p.fill(gx, yy, gx + gw, yy + 1, LINE);
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
