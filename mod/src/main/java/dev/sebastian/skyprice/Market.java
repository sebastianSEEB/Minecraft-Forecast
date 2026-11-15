package dev.sebastian.skyprice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;

/**
 * Small, pure market helpers shared by the tooltip, the AH highlight, the flip feed and the bazaar ranking.
 *
 *   liquidity      sales per day (24 h count, else 7-day count / 7) → time to sell when you're the cheapest
 *   confidence     high / medium / low from sales per day and how far the cheapest BIN is from fair value
 *   undercut       a clean price just under a competitor (what you'd list at)
 *   bazaar flip    buy order at top + 0.1, sell offer at top − 0.1, minus the bazaar tax
 */
final class Market {
	record Liquidity(double perDay, double etaHours, String confidence) {
		boolean known() {
			return perDay > 0;
		}
	}

	private Market() {}

	static double median(List<Double> v) {
		if (v == null || v.isEmpty()) return Double.NaN;
		List<Double> s = new ArrayList<>(v);
		s.sort(Double::compare);
		int n = s.size();
		return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
	}

	/** Sales per day of an auction item doc (0 = unknown / never sells). */
	static double salesPerDay(JsonObject d) {
		Double s24 = HudFormatter.num(HudFormatter.obj(d, "summary"), "sales");
		Double n7 = HudFormatter.num(HudFormatter.obj(d, "stats", "7d", "sold"), "n");
		double a = s24 == null ? 0 : s24, b = n7 == null ? 0 : n7 / 7.0;
		return Math.max(a, b);
	}

	/**
	 * How liquid an auction item is. ETA assumes you list just under the cheapest BIN: about one sale-interval.
	 * @param fair fair value (null = unknown), used to downgrade confidence when the cheapest BIN is far from it
	 */
	static Liquidity liquidity(JsonObject d, Double fair) {
		double perDay = salesPerDay(d);
		double eta = perDay > 0 ? 24.0 / perDay : Double.POSITIVE_INFINITY;
		int level = perDay >= 10 ? 2 : perDay >= 3 ? 1 : 0;
		Double lowest = HudFormatter.num(HudFormatter.obj(d, "market", "listed"), "lowest");
		if (lowest == null) lowest = HudFormatter.num(HudFormatter.obj(d, "summary"), "lbin");
		if (fair != null && fair > 0 && lowest != null && lowest > 0 && Math.abs(lowest / fair - 1) > 0.35) level = Math.max(0, level - 1);
		if (hasSevereFlag(d)) level = 0;
		return new Liquidity(perDay, eta, level == 2 ? "high" : level == 1 ? "medium" : "low");
	}

	static boolean hasSevereFlag(JsonObject d) {
		JsonObject m = HudFormatter.obj(d, "market");
		if (m == null || !m.has("flags") || !m.get("flags").isJsonArray()) return false;
		for (var e : m.getAsJsonArray("flags")) {
			if (!e.isJsonObject()) continue;
			JsonObject f = e.getAsJsonObject();
			if (f.has("level") && f.get("level").getAsInt() >= 2 && !"thin".equals(HudFormatter.str(f, "code"))) return true;
		}
		return false;
	}

	/** "<1h", "~3h", "~2d", "rarely sells" */
	static String eta(double hours) {
		if (Double.isInfinite(hours) || Double.isNaN(hours) || hours > 24 * 14) return "rarely sells";
		if (hours < 1) return "<1h";
		if (hours < 36) return "~" + Math.round(hours) + "h";
		return String.format(Locale.ROOT, "~%.0fd", hours / 24);
	}

	static int confidenceColor(String c) {
		return "high".equals(c) ? 0x55FF55 : "medium".equals(c) ? 0xFFFF55 : 0xFF5555;
	}

	/** Round to a "human" price step so listings look normal (100 / 1k / 10k steps). */
	static double niceStep(double p) {
		return p >= 100e6 ? 100_000 : p >= 10e6 ? 10_000 : p >= 1e6 ? 1_000 : p >= 10_000 ? 100 : 1;
	}

	/** Just under a competitor's price: about 0.5 % below, rounded down to a clean step. */
	static double undercut(double competitor) {
		if (competitor <= 1) return competitor;
		double step = niceStep(competitor);
		double target = competitor * 0.995;
		double r = Math.floor(target / step) * step;
		return r >= competitor ? competitor - step : Math.max(1, r);
	}

	// ------------------------------------------------------------------ bazaar
	record BzFlip(String id, double order, double offer, double perUnit, double pct, double weekly, double unitsPerHour,
			double coinsPerHour, int competition) {}

	/**
	 * Bazaar flip for one product: place a buy order 0.1 above the top order, then a sell offer 0.1 under the cheapest offer.
	 * @param instaBuy  quick_status buyPrice (cheapest sell offer)
	 * @param instaSell quick_status sellPrice (highest buy order)
	 * @param share     part of the market's weekly volume you can expect to catch (0.1 = 10 %)
	 * @param budget    coins you're willing to tie up in one product
	 */
	static BzFlip bazaarFlip(String id, double instaBuy, double instaSell, double buyWeek, double sellWeek, int orders,
			double taxPct, double share, double budget) {
		if (instaBuy <= 0 || instaSell <= 0) return null;
		double order = instaSell + 0.1, offer = instaBuy - 0.1;
		double perUnit = offer * (1 - taxPct / 100.0) - order;
		double pct = perUnit / order;
		double weekly = Math.min(buyWeek, sellWeek);
		double marketPerHour = weekly / 168.0;
		double mine = marketPerHour * share;
		double affordable = budget > 0 ? budget / order : mine;       // what one budget's worth of orders buys per cycle (~1 h)
		double units = Math.min(mine, affordable);
		return new BzFlip(id, order, offer, perUnit, pct, weekly, marketPerHour, Math.max(0, perUnit) * units, orders);
	}
}
