package dev.notenoughflips;

import java.util.function.BiConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Draw on the in-game HUD, on top of everything (GuiGraphicsExtractor version). */
final class HudHook {
	private HudHook() {}

	/** drawer gets a painter plus {gui-scaled width, height}. */
	static void register(String path, BiConsumer<Highlight.Painter, int[]> drawer) {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("notenoughflips", path), (graphics, tick) -> {
			Minecraft mc = Minecraft.getInstance();
			drawer.accept(new Highlight.Painter() {
				@Override
				public void fill(int x1, int y1, int x2, int y2, int argb) {
					graphics.fill(x1, y1, x2, y2, argb);
				}

				@Override
				public void text(String text, int x, int y, int argb) {
					graphics.text(mc.font, Component.literal(text), x, y, argb, false);
				}
			}, new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
		});
	}
}
