package dev.sebastian.skyprice;

import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

/**
 * Adds market lines (24h avg / high / low, lowest BIN, forecast) to the tooltip of any
 * Hypixel SkyBlock item. All numbers come from the local price server (run.py) on your
 * own PC — the mod never talks to Hypixel and never changes anything in-game.
 */
public final class SkyPriceClient implements ClientModInitializer {
	private static SkyPriceConfig config;
	private static PriceClient prices;

	@Override
	public void onInitializeClient() {
		config = SkyPriceConfig.load();
		prices = new PriceClient(config);

		ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipFlag, lines) -> {
			if (!config.enabled) return;
			String key = ItemKeys.keyFor(stack);
			if (key == null) return;              // not a SkyBlock item
			List<Component> extra = prices.lines(key);
			if (extra.isEmpty()) return;
			lines.add(Component.empty());
			lines.addAll(extra);
		});
	}
}
