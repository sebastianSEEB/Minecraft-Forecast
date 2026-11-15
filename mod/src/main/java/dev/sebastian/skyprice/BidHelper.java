package dev.sebastian.skyprice;

import java.util.Locale;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Click an auction on the Auction House -> the preset bid (the lowest valid bid: starting bid, or the
 * current bid + 2.5 %, never above 70 % of the lowest BIN) is copied to your clipboard and shown in chat. You then place the bid yourself (paste it into
 * the bid sign and confirm). The mod never clicks, bids or sends anything to the server:
 * automated bidding is a macro under Hypixel's rules.
 */
final class BidHelper {
	record Hover(String key, Double suggested, double nextBid, double cap, double maxProfitable, Double lowest, long at) {}

	private static volatile Hover last;

	private BidHelper() {}

	/** Called from the tooltip of a bid auction. */
	static void hovered(String key, Double suggested, double nextBid, double cap, double maxProfitable, Double lowest) {
		last = new Hover(key, suggested, nextBid, cap, maxProfitable, lowest, System.currentTimeMillis());
	}

	static void register(SkyPriceConfig config) {
		cfg = config;
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof AbstractContainerScreen<?>)) return;
			RenderHook.register(screen, (s, painter) -> {
				Highlight.paint(s, painter);
				OutbidHud.paint(s, painter);
				try {
					FcfPanel.paint(s, painter);
					if (s instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) Erp.paint(s, painter);
				} catch (RuntimeException e) {                 // never let a drawing bug break the inventory
					System.err.println("[SkyPrice] FCF/ERP panel: " + e);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
					!(s instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen && Erp.click(event.x(), event.y())));
			ScreenMouseEvents.afterMouseClick(screen).register((s, event, consumed) -> {
				if (OutbidHud.click(s, event.x(), event.y())) return true;
				if (FcfPanel.click(s, event.x(), event.y())) return true;
				if (cfg.copyBidOnClick) onClick(s);
				return consumed;
			});
		});
	}

	private static SkyPriceConfig cfg;

	private static void onClick(Screen screen) {
		Hover h = last;
		if (h == null || System.currentTimeMillis() - h.at() > 400) return;     // click must be on the auction you hover
		if (!screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("auction")) return;
		last = null;
		Minecraft mc = Minecraft.getInstance();
		if (h.suggested() == null) {
			Chat.say(HudFormatter.line("[SkyPrice] ", HudFormatter.GOLD,
					"Skip " + h.key() + ": the next valid bid " + HudFormatter.fmt(h.nextBid()) + " is above your cap "
							+ HudFormatter.fmt(h.cap()) + " (" + (int) cfg.maxBidPercentOfLowestBin + "% of lowest BIN).", HudFormatter.GRAY));
			return;
		}
		long amount = (long) Math.floor(h.suggested());
		mc.keyboardHandler.setClipboard(Long.toString(amount));
		if (cfg.watchBids) Watch.add(h.key(), amount, h.maxProfitable(), h.lowest());
		boolean profitable = h.suggested() <= h.maxProfitable();
		Chat.say(HudFormatter.line("[SkyPrice] ", HudFormatter.GOLD,
				"Copied bid " + String.format(Locale.ROOT, "%,d", amount) + " for " + h.key() + " (cap " + HudFormatter.fmt(h.cap())
						+ "). Paste it into the bid sign (Ctrl+V) and confirm yourself. ",
				HudFormatter.WHITE,
				profitable ? "✔ under your max profitable bid " + HudFormatter.fmt(h.maxProfitable())
						: "⚠ above your max profitable bid " + HudFormatter.fmt(h.maxProfitable()),
				profitable ? 0x55FF55 : HudFormatter.YELLOW));
		if (cfg.watchBids) {
			Chat.say(HudFormatter.line("[SkyPrice] ", HudFormatter.GOLD,
					"Watching " + h.key() + ": lowest BIN re-checked every " + cfg.watchRecheckSeconds + " s for "
							+ cfg.watchHours + " h (/skyprice watching, /skyprice unwatch <item>).", HudFormatter.GRAY));
		}
	}
}
