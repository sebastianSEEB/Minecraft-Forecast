package dev.notenoughflips;

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

	/** Short UI "ping" for alerts (item sold, outbid, order filled). Never throws. */
	static void ping() {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			try {
				mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
						net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 1.0F));
			} catch (RuntimeException ignored) {
				// no sound is fine
			}
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
