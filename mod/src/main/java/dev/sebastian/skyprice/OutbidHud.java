package dev.sebastian.skyprice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Panel to the right of your inventory listing auctions you've been outbid on (read from Hypixel's
 * "[Auction] X outbid you by ... for ITEM" chat messages, which carry a /viewauction link).
 * Click a row to open that auction and bid again yourself; click ✕ to dismiss it.
 * A row disappears when you place a new bid on that item, or after outbidHours.
 */
final class OutbidHud {
	record Entry(String item, String uuid, String outbidBy, long at) {}

	private static final List<Entry> entries = new CopyOnWriteArrayList<>();
	private static final Pattern OUTBID = Pattern.compile("outbid you by ([0-9][0-9,.]*[kKmMbB]?) coins for (.+?)(?:\\s+CLICK.*)?$");
	private static final Pattern BID_PLACED = Pattern.compile("Bid of ([0-9][0-9,.]*[kKmMbB]?) coins placed for (.+?)!");
	private static final int W = 150, ROW = 22, HEAD = 14;
	private static SkyPriceConfig cfg;

	private OutbidHud() {}

	static void init(SkyPriceConfig config) {
		cfg = config;
	}

	// ------------------------------------------------------------------ chat
	static void onChat(Component message) {
		String plain = message.getString().replaceAll("§.", "").trim();
		Matcher m = OUTBID.matcher(plain);
		if (plain.contains("outbid you") && m.find()) {
			String uuid = viewauctionId(message);
			if (uuid == null) return;
			entries.removeIf(e -> e.uuid().equals(uuid));
			entries.add(0, new Entry(m.group(2).trim(), uuid, m.group(1), System.currentTimeMillis()));
			while (entries.size() > 8) entries.remove(entries.size() - 1);
			return;
		}
		Matcher b = BID_PLACED.matcher(plain);
		if (b.find()) {
			String item = b.group(2).trim();
			entries.removeIf(e -> e.item().equalsIgnoreCase(item));   // you bid again: no longer outbid
		}
	}

	/** The auction id from the message's click action ("/viewauction <id>"). */
	private static String viewauctionId(Component message) {
		String[] found = {null};
		message.visit((Style style, String text) -> {
			ClickEvent ev = style.getClickEvent();
			if (found[0] == null && ev instanceof ClickEvent.RunCommand rc && rc.command().startsWith("/viewauction ")) {
				found[0] = rc.command().substring("/viewauction ".length()).trim();
			}
			return Optional.empty();
		}, Style.EMPTY);
		return found[0];
	}

	// ------------------------------------------------------------------ panel
	private static int[] origin(Screen s) {
		int[] box = GuiPos.box(s);
		int x = Math.min(box[0] + box[2] + 6, s.width - W - 4);
		return new int[]{x, box[1] + 18};                                // below the FLIP tab
	}

	private static List<Entry> live() {
		long now = System.currentTimeMillis();
		entries.removeIf(e -> now - e.at() > cfg.outbidHours * 3_600_000L);
		return entries;
	}

	static void paint(Screen s, Highlight.Painter p) {
		if (cfg == null || !cfg.outbidHud || !(s instanceof InventoryScreen) || FlipPanel.isOpen()) return;
		List<Entry> list = live();
		if (list.isEmpty()) return;
		int[] o = origin(s);
		int x = o[0], y = o[1], h = HEAD + list.size() * ROW + 4;
		p.fill(x, y, x + W, y + h, 0xD0100010);
		p.fill(x, y, x + W, y + 1, 0xFF5000A0);
		p.fill(x, y + h - 1, x + W, y + h, 0xFF5000A0);
		p.text("Outbid on (" + list.size() + ")", x + 5, y + 4, 0xFFFFAA00);
		var font = Minecraft.getInstance().font;
		for (int i = 0; i < list.size(); i++) {
			Entry e = list.get(i);
			int ry = y + HEAD + i * ROW;
			String name = e.item();
			while (name.length() > 3 && font.width(name) > W - 22) name = name.substring(0, name.length() - 2);
			if (!name.equals(e.item())) name = name + "…";
			p.text(name, x + 5, ry + 2, 0xFFFFFFFF);
			long mins = (System.currentTimeMillis() - e.at()) / 60000;
			p.text("+" + e.outbidBy() + " · " + (mins < 60 ? mins + "m" : mins / 60 + "h") + " ago", x + 5, ry + 12, 0xFFAAAAAA);
			p.text("Bid ▶", x + W - 34, ry + 12, 0xFF55FFFF);
			p.text("✕", x + W - 10, ry + 2, 0xFF777777);
		}
	}

	/** Returns true if the click hit the panel. */
	static boolean click(Screen s, double mx, double my) {
		if (cfg == null || !cfg.outbidHud || !(s instanceof InventoryScreen) || FlipPanel.isOpen()) return false;
		List<Entry> list = live();
		if (list.isEmpty()) return false;
		int[] o = origin(s);
		int x = o[0], y = o[1];
		if (mx < x || mx > x + W || my < y + HEAD || my > y + HEAD + list.size() * ROW) return false;
		int i = (int) ((my - y - HEAD) / ROW);
		if (i < 0 || i >= list.size()) return false;
		Entry e = list.get(i);
		if (mx >= x + W - 13 && my < y + HEAD + i * ROW + 11) {               // ✕ dismiss
			entries.remove(e);
			return true;
		}
		Chat.command("viewauction " + e.uuid());                               // you asked to open it; you bid yourself
		return true;
	}

	static List<Entry> list() {
		return new ArrayList<>(live());
	}

	static String describe(Entry e) {
		return String.format(Locale.ROOT, "%s (+%s)", e.item(), e.outbidBy());
	}
}
