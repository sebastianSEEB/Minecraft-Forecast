package dev.sebastian.skyprice;

import java.util.function.BiConsumer;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.Screen;

/** Draw on top of a screen after it renders, Minecraft 1.21.11 version (GuiGraphics). */
final class RenderHook {
	private RenderHook() {}

	static void register(Screen screen, BiConsumer<Screen, Highlight.Painter> drawer) {
		ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, tickDelta) ->
				drawer.accept(s, (x1, y1, x2, y2, argb) -> graphics.fill(x1, y1, x2, y2, argb)));
	}
}
