package dev.sebastian.skyprice;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.component.ItemLore;

/**
 * Yellow border around auctions in the AH that are worth it: underpriced enough to flip for at least
 * minFlipProfitPercent after fees (or worth bidding on), the item sells regularly (>= 3 sales in 24 h)
 * and no manipulation is flagged. Strong deals (>= 20 % under fair value) get a filled glow as well.
 * Drawing only: nothing is clicked or sent.
 */
final class Highlight {
	interface Painter {
		void fill(int x1, int y1, int x2, int y2, int argb);

		void text(String text, int x, int y, int argb);
	}

	private record Cached(int stackId, long at, int level) {}   // level: 0 none, 1 worth it, 2 strong deal

	private static final Map<Integer, Cached> cache = new HashMap<>();
	private static SkyPriceConfig cfg;
	private static DataClient data;

	private Highlight() {}

	static void init(SkyPriceConfig config, DataClient client) {
		cfg = config;
		data = client;
	}

	static void paint(Screen screen, Painter p) {
		if (cfg == null || !cfg.highlightDeals || !(screen instanceof AbstractContainerScreen<?> acs)) return;
		if (!screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("auction")) return;
		if (!(acs.getMenu() instanceof ChestMenu chest)) return;
		int rows = chest.getRowCount();
		int imageHeight = 114 + rows * 18;                        // vanilla chest screen layout
		int left = (screen.width - 176) / 2;
		int top = (screen.height - imageHeight) / 2;
		long now = System.currentTimeMillis();
		List<Slot> slots = chest.slots;
		for (int i = 0; i < Math.min(rows * 9, slots.size()); i++) {
			Slot slot = slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) { cache.remove(i); continue; }
			int id = System.identityHashCode(stack) * 31 + stack.getCount();
			Cached c = cache.get(i);
			if (c == null || c.stackId() != id || now - c.at() > 2000) {
				c = new Cached(id, now, level(stack));
				cache.put(i, c);
			}
			if (c.level() == 0) continue;
			int x = left + slot.x, y = top + slot.y;
			int border = c.level() == 2 ? 0xFFFFD700 : 0xFFFFE066;
			if (c.level() == 2) p.fill(x, y, x + 16, y + 16, 0x50FFD700);      // glow fill for strong deals
			p.fill(x - 1, y - 1, x + 17, y, border);
			p.fill(x - 1, y + 16, x + 17, y + 17, border);
			p.fill(x - 1, y, x, y + 16, border);
			p.fill(x + 16, y, x + 17, y + 16, border);
		}
	}

	private static int level(ItemStack stack) {
		try {
			String key = ItemKeys.keyFor(stack);
			if (key == null) return 0;
			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null) return 0;
			List<Component> lines = lore.lines();
			Verdict.Listing listing = Verdict.listing(lines, stack.getCount());
			if (listing == null) return 0;                         // not an auction listing
			DataClient.Doc d = data.doc(key);
			if (d == null || d.json() == null) return 0;
			Valuation.Estimate est = cfg.valueUpgrades ? Valuation.estimate(d.json(), key, Valuation.parse(stack)) : null;
			Verdict.Result r = Verdict.evaluate(d.json(), listing, null, cfg, est);
			if (r.banner() == null) return 0;                      // not profitable / not liquid / risky
			Double fair = est != null ? Double.valueOf(est.value()) : HudFormatter.num(HudFormatter.obj(d.json(), "market"), "fair");
			return fair != null && listing.price() <= fair * (1 - cfg.dealMinDiscountPercent / 100.0) ? 2 : 1;
		} catch (RuntimeException e) {
			return 0;
		}
	}
}
