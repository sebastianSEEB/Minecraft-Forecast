package dev.sebastian.skyprice;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-side chat message, Minecraft 1.21.11 version. */
final class Chat {
	private Chat() {}

	static void say(Component c) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.displayClientMessage(c, false);
		});
	}

	/** Runs a server command the player asked for (e.g. "viewauction <id>"), without the leading slash. */
	static void command(String cmd) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.connection.sendCommand(cmd);
		});
	}
}
