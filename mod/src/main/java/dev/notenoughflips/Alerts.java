package dev.notenoughflips;

import java.util.Locale;

import net.minecraft.network.chat.Component;

/**
 * One place for Not Enough Flips alerts: a chat line, plus a ping when alertSound is on. Also reacts to Hypixel's own chat
 * messages (your item sold, you were outbid) so they ping, and feeds them to the profit tracker / order tracker.
 */
final class Alerts {
	private static NefConfig cfg;

	private Alerts() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	static void alert(Component c) {
		Chat.say(c);
		if (cfg != null && cfg.alertSound) Chat.ping();
	}

	/** Every incoming chat line (not the action bar). */
	static void onChat(Component message) {
		String plain = message.getString().replaceAll("§.", "").trim();
		try {
			ProfitLog.onChat(plain);
			BzOrders.onChat(plain);
			FlipPanel.onChat(plain);
		} catch (RuntimeException e) {
			System.err.println("[NEF] chat: " + e);
		}
		String low = plain.toLowerCase(Locale.ROOT);
		boolean ping = low.startsWith("[auction]") && (low.contains(" bought ") || low.contains("outbid you"));
		if (ping && cfg != null && cfg.alertSound) Chat.ping();
	}
}
