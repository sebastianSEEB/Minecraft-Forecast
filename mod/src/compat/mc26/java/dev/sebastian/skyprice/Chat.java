package dev.sebastian.skyprice;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-side chat message, Minecraft 26.x version. */
final class Chat {
	private Chat() {}

	static void say(Component c) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendSystemMessage(c);
		});
	}
}
