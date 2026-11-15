package dev.sebastian.skyprice;

import com.google.gson.JsonObject;
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

	private record Cached(int stackId, long at, int level, String name, double price, double profit, double eta, boolean bid) {
		Cached(int stackId, long at) {
			this(stackId, at, 0, null, 0, 0, Double.NaN, false);
		}
	}   // level: 0 none, 1 worth it, 2 strong deal

	/** A profitable auction on the AH page you're looking at (for the FLIP panel's "On this page"). */
	record PageFlip(int slot, int rank, String name, double price, double profit, double eta, boolean bid) {}

	private static final Map<Integer, Cached> cache = new HashMap<>();
	private static volatile List<PageFlip> page = List.of();
	private static volatile long pageAt;
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
		int left, top;
		long now = System.currentTimeMillis();
		List<Slot> slots = chest.slots;
		int[] box = GuiPos.box(screen);
		left = box[0];
		top = box[1];
		List<PageFlip> found = new java.util.ArrayList<>();
		for (int i = 0; i < Math.min(rows * 9, slots.size()); i++) {
			Slot slot = slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) { cache.remove(i); continue; }
			int id = System.identityHashCode(stack) * 31 + stack.getCount();
			Cached c = cache.get(i);
			if (c == null || c.stackId() != id || now - c.at() > 2000) {
				c = evaluate(stack, id, now);
				cache.put(i, c);
			}
			if (c.level() == 0) continue;
			found.add(new PageFlip(i, 0, c.name(), c.price(), c.profit(), c.eta(), c.bid()));
			int x = left + slot.x, y = top + slot.y;
			int border = c.level() == 2 ? 0xFFFFD700 : 0xFFFFE066;
			if (c.level() == 2) p.fill(x, y, x + 16, y + 16, 0x50FFD700);      // glow fill for strong deals
			p.fill(x - 1, y - 1, x + 17, y, border);
			p.fill(x - 1, y + 16, x + 17, y + 17, border);
			p.fill(x - 1, y, x, y + 16, border);
			p.fill(x + 16, y, x + 17, y + 16, border);
		}
		found.sort((a, b) -> Double.compare(b.profit(), a.profit()));
		List<PageFlip> ranked = new java.util.ArrayList<>();
		for (int r = 0; r < found.size(); r++) {
			PageFlip f = found.get(r);
			ranked.add(new PageFlip(f.slot(), r + 1, f.name(), f.price(), f.profit(), f.eta(), f.bid()));
			if (r < 5) {                                                    // rank number, matches the FLIP panel
				Slot slot = slots.get(f.slot());
				int x = left + slot.x, y = top + slot.y;
				p.fill(x, y, x + 7, y + 9, 0xE0000000);
				p.text(String.valueOf(r + 1), x + 1, y, 0xFF55FF55);
			}
		}
		page = ranked;
		pageAt = now;
	}

	/** Profitable auctions on the AH page currently open (best first), empty when no AH page is open. */
	static List<PageFlip> page() {
		return System.currentTimeMillis() - pageAt < 1500 ? page : List.of();
	}

	private static Cached evaluate(ItemStack stack, int id, long now) {
		try {
			String key = ItemKeys.keyFor(stack);
			if (key == null) return new Cached(id, now);
			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null) return new Cached(id, now);
			List<Component> lines = lore.lines();
			if (Verdict.ownListing(lines, stack.getCount(), SkyPriceClient.playerName()) != null) return new Cached(id, now);
			Verdict.Listing listing = Verdict.listing(lines, stack.getCount());
			if (listing == null) return new Cached(id, now);                // not an auction listing
			JsonObject dj = SkyPriceClient.docFor(key, false);              // SkyCofl only if already cached (no burst of requests)
			if (dj == null) return new Cached(id, now);
			Valuation.Estimate est = cfg.valueUpgrades ? Valuation.estimate(dj, key, Valuation.parse(stack)) : null;
			Verdict.Result r = Verdict.evaluate(dj, listing, null, cfg, est);
			if (r.banner() == null || r.profit() == null) return new Cached(id, now);   // not profitable / not liquid / risky
			Double fair = est != null ? Double.valueOf(est.value()) : Verdict.fair(dj);
			int level = fair != null && listing.price() <= fair * (1 - cfg.dealMinDiscountPercent / 100.0) ? 2 : 1;
			String name = stack.getHoverName().getString().replaceAll("§.", "");
			double eta = r.etaHours() == null ? Double.NaN : r.etaHours();
			return new Cached(id, now, level, name, listing.bin() ? listing.price() : r.suggestedBid(), r.profit(), eta, !listing.bin());
		} catch (RuntimeException e) {
			return new Cached(id, now);
		}
	}

}
