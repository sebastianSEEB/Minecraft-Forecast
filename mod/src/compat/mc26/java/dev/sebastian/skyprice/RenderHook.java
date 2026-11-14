package dev.sebastian.skyprice;

import java.util.function.BiConsumer;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.Screen;

/** Draw on top of a screen after it renders, Minecraft 26.x version (GuiGraphicsExtractor). */
final class RenderHook {
	private RenderHook() {}

	static void register(Screen screen, BiConsumer<Screen, Highlight.Painter> drawer) {
		ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) ->
				drawer.accept(s, (x1, y1, x2, y2, argb) -> graphics.fill(x1, y1, x2, y2, argb)));
	}
}
