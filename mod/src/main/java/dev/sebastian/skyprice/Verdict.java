package dev.sebastian.skyprice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * "Is this a good buy?" — compares the price you're looking at with the item's fair value.
 *
 *  Auction House: the price is read from the listing's own tooltip ("Buy it now: …", "Top bid: …",
 *  "Starting bid: …", per item for stacks). Without a listing (e.g. in your inventory) the cheapest BIN is judged.
 *  Fair value = median of the hourly median sale prices over the last 72 h (fallbacks: 7-day average, lowest BIN).
 *  Bazaar: the live instant-buy price vs the 7-day average buy price.
 *
 *  Flip estimate uses Hypixel AH fees: BIN listing 1 % (< 10M), 2 % (10M–100M), 2.5 % (≥ 100M),
 *  plus 1 % claim tax above 1M.
 */
final class Verdict {
	private static final Pattern PRICE = Pattern.compile("(Buy it now|Top bid|Starting bid):\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*coins",
			Pattern.CASE_INSENSITIVE);

	private Verdict() {}

	/** Price per item of the auction listing shown in this tooltip, or null if it isn't a listing. */
	static Double listingPrice(List<Component> lore, int count) {
		Double bin = null, top = null, start = null;
		for (Component c : lore) {
			Matcher m = PRICE.matcher(c.getString().replaceAll("§.", ""));
			if (!m.find()) continue;
			double v = Double.parseDouble(m.group(2).replace(",", ""));
			switch (m.group(1).toLowerCase(Locale.ROOT)) {
				case "buy it now" -> bin = v;
				case "top bid" -> top = v;
				default -> start = v;
			}
		}
		Double p = bin != null ? bin : top != null ? top : start;
		return p == null ? null : p / Math.max(1, count);
	}

	static double listingFee(double price) {
		return price >= 100e6 ? 0.025 : price >= 10e6 ? 0.02 : 0.01;
	}

	static double netAfterFees(double sell) {
		return sell * (1 - listingFee(sell) - (sell > 1e6 ? 0.01 : 0));
	}

	/** Fair value for an auction item: median of recent hourly medians. */
	static Double fairAuction(JsonObject d) {
		JsonObject series = HudFormatter.obj(d, "series");
		if (series != null && series.has("median") && series.get("median").isJsonArray()) {
			JsonArray a = series.getAsJsonArray("median");
			List<Double> v = new ArrayList<>();
			for (int i = Math.max(0, a.size() - 72); i < a.size(); i++) {
				JsonElement e = a.get(i);
				if (!e.isJsonNull()) v.add(e.getAsDouble());
			}
			if (v.size() >= 5) {
				v.sort(Double::compare);
				int n = v.size();
				return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
			}
		}
		Double w7 = HudFormatter.num(HudFormatter.obj(d, "stats", "7d", "sold"), "avg");
		if (w7 != null) return w7;
		Double avg = HudFormatter.num(HudFormatter.obj(d, "summary"), "avg");
		return avg != null ? avg : HudFormatter.num(HudFormatter.obj(d, "summary"), "lbin");
	}

	static List<Component> lines(JsonObject d, Double listing, Double liveBuy) {
		List<Component> out = new ArrayList<>();
		boolean bz = "bz".equals(d.get("kind").getAsString());
		JsonObject s = HudFormatter.obj(d, "summary");
		Double price, fair;
		String what, basis;
		if (bz) {
			price = liveBuy != null ? liveBuy : HudFormatter.num(s, "now");
			fair = HudFormatter.num(HudFormatter.obj(d, "stats", "7d", "buy"), "avg");
			if (fair == null) fair = HudFormatter.num(s, "avg");
			what = "Buy price";
			basis = "7-day avg";
		} else {
			price = listing != null ? listing : HudFormatter.num(s, "lbin");
			fair = fairAuction(d);
			what = listing != null ? "This listing" : "Cheapest BIN";
			basis = "fair value";
		}
		if (price == null || fair == null || fair <= 0) return out;

		double diff = price / fair - 1;                       // <0 = cheaper than fair
		String pct = String.format(Locale.ROOT, "%.1f%%", Math.abs(diff) * 100);
		String label;
		int color;
		if (!bz && diff <= -(listingFee(fair) + 0.01 + 0.08)) { label = "GREAT BUY"; color = 0x55FF55; }
		else if (diff <= -0.03) { label = "GOOD BUY"; color = 0x55FF55; }
		else if (diff < 0.03) { label = "FAIR PRICE"; color = 0xFFFF55; }
		else if (diff < 0.15) { label = "OVERPRICED"; color = 0xFFAA00; }
		else { label = "BAD BUY"; color = 0xFF5555; }

		MutableComponent l = Component.empty()
				.append(HudFormatter.seg(" ➜ " + label, color))
				.append(HudFormatter.seg(": " + what + " " + HudFormatter.fmt(price) + " is " + pct
						+ (diff < 0 ? " under " : " over ") + basis + " (" + HudFormatter.fmt(fair) + ")", HudFormatter.GRAY));
		out.add(l);

		if (!bz) {
			double profit = netAfterFees(fair) - price;
			if (profit > 0) {
				out.add(HudFormatter.line("    flip ≈ ", HudFormatter.GRAY, "+" + HudFormatter.fmt(profit), 0x55FF55,
						" after AH fees if resold at fair value", HudFormatter.DARK));
			}
			Double sales = HudFormatter.num(s, "sales");
			if (sales == null || sales < 3) {
				out.add(HudFormatter.line("    ⚠ only " + (sales == null ? 0 : sales.intValue()) + " sales in 24h: harder to resell, value less certain",
						HudFormatter.YELLOW));
			}
		}

		// timing hints: forecast down, or a seasonal dip coming soon
		JsonObject f = HudFormatter.obj(d, "forecast", bz ? "buy" : "main");
		JsonObject f24 = HudFormatter.obj(f, "h", "24");
		Double last = HudFormatter.num(f, "last");
		if (f24 != null && last != null && last > 0) {
			double fc = HudFormatter.num(f24, "f") / last - 1;
			if (fc <= -0.03) out.add(HudFormatter.line(String.format(Locale.ROOT, "    ⏳ forecast: %.1f%% lower in 24h, waiting may be cheaper", fc * 100), HudFormatter.AQUA));
			else if (fc >= 0.03 && diff < 0.03) out.add(HudFormatter.line(String.format(Locale.ROOT, "    ↗ forecast: +%.1f%% in 24h, buying now looks good", fc * 100), HudFormatter.AQUA));
		}
		if (d.has("seasons") && d.get("seasons").isJsonArray()) {
			for (JsonElement e : d.getAsJsonArray("seasons")) {
				JsonObject se = e.getAsJsonObject();
				if (!se.get("confirmed").getAsBoolean()) continue;
				long dip = HudFormatter.nextAt(HudFormatter.num(se, "trough_at"), HudFormatter.num(se, "period"));
				double hours = (dip - System.currentTimeMillis()) / 3_600_000.0;
				Double fall = HudFormatter.num(se, "fall_to_trough");
				if (hours >= 1 && hours <= 24 && fall != null && fall <= -0.02) {
					out.add(HudFormatter.line(String.format(Locale.ROOT, "    ⏳ %s dip %s (%.1f%%): consider waiting",
							se.get("label").getAsString().toLowerCase(Locale.ROOT), HudFormatter.until(dip), fall * 100), 0xFF55FF));
					break;
				}
			}
		}
		return out;
	}
}
