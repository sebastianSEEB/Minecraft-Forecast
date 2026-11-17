package dev.sebastian.skyprice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bazaar flip ranking over every product (live quick_status from the public Hypixel API):
 *
 *   buy order at top + 0.1  →  sell offer at cheapest − 0.1  →  profit per unit after the bazaar tax
 *   sized by volume: your buy order fills from instant sells, your offer from instant buys (bazaarSharePercent of each),
 *   as many as fit one round of bazaarRoundHours and your budget → total profit, round time, profit per hour.
 *
 * With forecasting on, the sell offer's price is moved by your collector's forecast for the time it sells (see
 * {@link Forecasts}), so a product that's about to drop ranks lower and one that's rising (beyond normal error) higher.
 *
 * Skipped: margins under 2 % or over 100 % (thin or manipulated), fewer than 10 units traded per hour, products
 * whose buy order alone costs more than your budget. Also turns a display name ("Enchanted Diamond") back into the
 * product id for the Bazaar menus and your orders.
 */
final class Bazaar {
	private static volatile List<Market.BzFlip> ranked = List.of();
	private static volatile long rankedAt;
	private static volatile double rankedBudget = -1;
	private static volatile Map<String, String> prettyNames = Map.of();
	private static volatile int prettyFor = -1;

	private static volatile DataClient data;
	private static volatile boolean forecastOn;

	private Bazaar() {}

	/**
	 * Best bazaar flips for this budget, best profit/hour first. A snapshot refreshed every bazaarRefreshMinutes (or when
	 * your budget changes by more than 10 %), so the list doesn't jump around while you're placing orders.
	 */
	static List<Market.BzFlip> ranking(SkyPriceConfig cfg, DataClient data, double budget) {
		long now = System.currentTimeMillis();
		Bazaar.data = data;
		Map<String, DataClient.Live> all = data.bazaarAll();
		if (forecastOn != cfg.forecastFlips) {                    // toggled: re-rank now
			forecastOn = cfg.forecastFlips;
			rankedAt = 0;
		}
		boolean budgetMoved = rankedBudget <= 0 || Math.abs(rankedBudget - budget) > rankedBudget * 0.10;
		if (!ranked.isEmpty() && !budgetMoved && now - rankedAt < Math.max(1, cfg.bazaarRefreshMinutes) * 60_000L) return ranked;
		if (all.isEmpty()) return ranked;
		List<Market.BzFlip> out = new ArrayList<>();
		for (Map.Entry<String, DataClient.Live> e : all.entrySet()) {
			DataClient.Live l = e.getValue();
			Market.BzFlip f = flip(cfg, e.getKey(), l, budget);
			if (f == null || f.perUnit() <= 0 || f.pct() < 0.02 || f.pct() > cfg.bazaarMaxSpreadPercent / 100.0) continue;
			if (f.unitsPerHour() < 10 || f.qty() < 1 || f.totalProfit() < 1000) continue;
			out.add(f);
		}
		out.sort((a, b) -> Double.compare(b.coinsPerHour(), a.coinsPerHour()));
		ranked = out.size() > 50 ? new ArrayList<>(out.subList(0, 50)) : out;
		Map<String, Integer> r = new HashMap<>();
		for (int i = 0; i < ranked.size(); i++) r.put(ranked.get(i).id(), i + 1);
		ranks = r;
		rankedAt = now;
		rankedBudget = budget;
		return ranked;
	}

	private static volatile Map<String, Integer> ranks = Map.of();

	/** Rank (1 = best) of a product in the current snapshot, or 0. */
	static int rank(String id) {
		Integer r = id == null ? null : ranks.get(id);
		return r == null ? 0 : r;
	}

	/** When the current snapshot was made (0 = none yet). */
	static long snapshotAt() {
		return rankedAt;
	}

	/** Volume-sized flip for one product with your settings. */
	static Market.BzFlip flip(SkyPriceConfig cfg, String id, DataClient.Live l, double budget) {
		DataClient dc = data != null ? data : SkyPriceClient.data();
		Forecasts.Side fc = cfg.forecastFlips && dc != null ? dc.forecast(id, "buy") : null;
		return Market.bazaarFlip(id, l.buy(), l.sell(), l.buyWeek(), l.sellWeek(), l.orders(), cfg.bazaarTaxPercent,
				cfg.bazaarSharePercent / 100.0, budget, Math.max(0.25, cfg.bazaarRoundHours), fc);
	}

	/** "Enchanted Diamond" (any colour codes, "64x " prefixes, "BUY "/"SELL " prefixes) → ENCHANTED_DIAMOND, or null. */
	static String idForName(String display, DataClient data) {
		if (display == null) return null;
		String n = display.replaceAll("§.", "").trim();
		n = n.replaceFirst("^(?i)(BUY|SELL)\\s+", "").replaceFirst("^[0-9,]+x\\s+", "").trim().toLowerCase(Locale.ROOT);
		if (n.isEmpty()) return null;
		Map<String, DataClient.Live> all = data.bazaarAll();
		Map<String, String> names = data.itemNames();
		if (names != null) {
			String id = names.get(n);
			if (id != null && all.containsKey(id)) return id;
		}
		Map<String, String> pretty = prettyNames;
		if (prettyFor != all.size()) {
			Map<String, String> m = new HashMap<>();
			for (String id : all.keySet()) m.put(Erp.pretty(id).toLowerCase(Locale.ROOT), id);
			prettyNames = pretty = m;
			prettyFor = all.size();
		}
		return pretty.get(n);
	}

	private static volatile Map<String, String> byId = Map.of();
	private static volatile Map<String, String> byIdFor;

	/** Display name of a product id (Hypixel's own name when known). */
	static String name(String id, DataClient data) {
		Map<String, String> names = data.itemNames();
		if (names != null && byIdFor != names) {
			Map<String, String> m = new HashMap<>();
			for (Map.Entry<String, String> e : names.entrySet()) m.putIfAbsent(e.getValue(), titleCase(e.getKey()));
			byId = m;
			byIdFor = names;
		}
		String n = byId.get(id);
		return n != null ? n : Erp.pretty(id);
	}

	private static String titleCase(String lower) {
		StringBuilder sb = new StringBuilder();
		for (String w : lower.split(" ")) {
			if (w.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return sb.toString();
	}
}
