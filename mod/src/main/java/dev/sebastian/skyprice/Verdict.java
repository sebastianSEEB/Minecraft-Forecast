package dev.sebastian.skyprice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * "Is this worth buying / bidding on?"
 *
 *  Price: read from the hovered auction's own tooltip ("Buy it now", "Top bid", "Starting bid"; per item for stacks).
 *  Fair value: from the collector (median of clean 72 h sales, cheapest-25 % listings and 7-day average);
 *              fallback: median of hourly median sales.
 *  Resale: fair value, capped just under the next-cheapest competing BIN.
 *  Profit: resale after AH fees (BIN listing 1 % < 10M, 2 % 10M-100M, 2.5 % >= 100M; 1 % claim tax above 1M) minus price.
 *
 *  BIN listing:  PROFIT FLIP if profit >= minFlipProfitPercent.
 *  Bid auction:  WORTH BIDDING if the current bid is below the max profitable bid
 *                = resale after fees / (1 + minFlipProfitPercent).
 *  No banner when the item barely sells (< 3 sales in 24 h) or the market looks manipulated.
 */
final class Verdict {
	private static final Pattern PRICE = Pattern.compile("(Buy it now|Top bid|Starting bid):\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*coins",
			Pattern.CASE_INSENSITIVE);

	/** price per item; bin = Buy It Now; bids = an auction that already has bids */
	record Listing(double price, boolean bin, boolean bids) {}

	record Result(List<Component> lines, Component banner) {}

	private Verdict() {}

	static Listing listing(List<Component> lore, int count) {
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
		int n = Math.max(1, count);
		if (bin != null) return new Listing(bin / n, true, false);
		if (top != null) return new Listing(top / n, false, true);
		if (start != null) return new Listing(start / n, false, false);
		return null;
	}

	static double listingFee(double price) {
		return price >= 100e6 ? 0.025 : price >= 10e6 ? 0.02 : 0.01;
	}

	static double netAfterFees(double sell) {
		return sell * (1 - listingFee(sell) - (sell > 1e6 ? 0.01 : 0));
	}

	/** Fallback fair value for an auction item: median of recent hourly medians. */
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

	private static MutableComponent bold(String text, int color) {
		return Component.literal(text).withStyle(ChatFormatting.BOLD).withColor(color);
	}

	static Result evaluate(JsonObject d, Listing listing, Double liveBuy, SkyPriceConfig cfg) {
		List<Component> out = new ArrayList<>();
		Component banner = null;
		boolean bz = "bz".equals(d.get("kind").getAsString());
		JsonObject s = HudFormatter.obj(d, "summary");
		JsonObject market = HudFormatter.obj(d, "market");
		Double riskD = HudFormatter.num(market, "risk");
		int risk = riskD == null ? 0 : riskD.intValue();
		double minPct = Math.max(0, cfg.minFlipProfitPercent) / 100.0;

		Double price, fair;
		String what, basis;
		if (bz) {
			price = liveBuy != null ? liveBuy : HudFormatter.num(s, "now");
			fair = HudFormatter.num(HudFormatter.obj(d, "stats", "7d", "buy"), "avg");
			if (fair == null) fair = HudFormatter.num(s, "avg");
			what = "Buy price";
			basis = "7-day avg";
		} else {
			price = listing != null ? Double.valueOf(listing.price()) : HudFormatter.num(s, "lbin");
			Double mf = HudFormatter.num(market, "fair");
			fair = mf != null ? mf : fairAuction(d);
			what = listing == null ? "Cheapest BIN" : listing.bin() ? "This BIN" : listing.bids() ? "Current bid" : "Starting bid";
			basis = "fair value";
		}
		if (price == null || fair == null || fair <= 0) return new Result(out, null);

		double diff = price / fair - 1;
		String pct = String.format(Locale.ROOT, "%.1f%%", Math.abs(diff) * 100);
		String label;
		int color;
		if (!bz && diff <= -(listingFee(fair) + 0.01 + 0.08)) { label = "GREAT BUY"; color = 0x55FF55; }
		else if (diff <= -0.03) { label = "GOOD BUY"; color = 0x55FF55; }
		else if (diff < 0.03) { label = "FAIR PRICE"; color = 0xFFFF55; }
		else if (diff < 0.15) { label = "OVERPRICED"; color = 0xFFAA00; }
		else { label = "BAD BUY"; color = 0xFF5555; }
		if (risk >= 2 && diff < 0.03) { label = "RISKY BUY"; color = 0xFFAA00; }
		if (listing != null && !listing.bin()) { label = "BID CHECK"; color = 0x55FFFF; }

		out.add(Component.empty()
				.append(HudFormatter.seg(" ➜ " + label, color))
				.append(HudFormatter.seg(": " + what + " " + HudFormatter.fmt(price) + " is " + pct
						+ (diff < 0 ? " under " : " over ") + basis + " (" + HudFormatter.fmt(fair) + ")", HudFormatter.GRAY)));

		if (!bz) {
			Double sales = HudFormatter.num(s, "sales");
			boolean liquid = sales != null && sales >= 3;
			if (market != null) {
				out.add(HudFormatter.line("    fair = sold " + HudFormatter.fmt(HudFormatter.num(market, "sold_median_72h"))
						+ " · cheapest 25% listed " + HudFormatter.fmt(HudFormatter.num(HudFormatter.obj(market, "listed"), "p25"))
						+ " · 7d avg " + HudFormatter.fmt(HudFormatter.num(market, "avg_7d")), HudFormatter.DARK));
				JsonObject li = HudFormatter.obj(market, "listed");
				if (li != null) {
					Double cnt = HudFormatter.num(li, "count");
					out.add(HudFormatter.line("    listed: ", HudFormatter.GRAY, (cnt == null ? 0 : cnt.intValue()) + "", HudFormatter.WHITE,
							"  lowest ", HudFormatter.GRAY, HudFormatter.fmt(HudFormatter.num(li, "lowest")), HudFormatter.YELLOW,
							"  2nd ", HudFormatter.GRAY, HudFormatter.fmt(HudFormatter.num(li, "second")), HudFormatter.WHITE,
							"  median ", HudFormatter.GRAY, HudFormatter.fmt(HudFormatter.num(li, "median")), HudFormatter.WHITE));
				}
			}
			Double resale = HudFormatter.num(market, "resale");          // assumes you bought the cheapest BIN
			Double lowest = HudFormatter.num(HudFormatter.obj(market, "listed"), "lowest");
			boolean notCheapest = listing != null && lowest != null && (!listing.bin() || listing.price() > lowest * 1.001);
			if (notCheapest) resale = Math.min(fair, lowest * 0.99);       // the cheapest BIN is still out there to compete with
			double sellAt = resale != null ? resale : fair;
			double net = netAfterFees(sellAt);

			if (listing != null && !listing.bin()) {
				double maxBid = net / (1 + minPct);
				boolean room = listing.price() < maxBid * 0.95;      // leave room for the next bid step
				out.add(HudFormatter.line("    max profitable bid: ", HudFormatter.GRAY, HudFormatter.fmt(maxBid), 0x55FF55,
						String.format(Locale.ROOT, "  (>= %.0f%% profit reselling at %s after fees)", minPct * 100, HudFormatter.fmt(sellAt)),
						HudFormatter.DARK));
				if (room && liquid && risk < 2) {
					banner = bold("✔ WORTH BIDDING: bid up to " + HudFormatter.fmt(maxBid)
							+ " → +" + HudFormatter.fmt(net - listing.price()) + " profit at current bid", 0x55FF55);
					out.add(HudFormatter.line("    ✔ worth bidding: +" + HudFormatter.fmt(net - listing.price()) + " at the current bid, +"
							+ HudFormatter.fmt(net - maxBid) + " even at your max bid", 0x55FF55));
				} else if (!room) {
					out.add(HudFormatter.line("    ✘ already bid past the profitable price", HudFormatter.RED));
				}
			} else {
				double profit = net - price;
				double ppct = profit / price;
				if (profit > 0) {
					out.add(HudFormatter.line("    flip ≈ ", HudFormatter.GRAY, "+" + HudFormatter.fmt(profit), 0x55FF55,
							String.format(Locale.ROOT, " (%.1f%%) after AH fees, reselling at %s", ppct * 100, HudFormatter.fmt(sellAt))
									+ (resale != null ? " (just under the next-cheapest BIN)" : ""), HudFormatter.DARK));
				} else {
					out.add(HudFormatter.line("    ✘ no flip profit after fees (resale ≈ " + HudFormatter.fmt(sellAt) + ")", HudFormatter.DARK));
				}
				if (ppct >= minPct && liquid && risk < 2) {
					banner = bold(String.format(Locale.ROOT, "✔ PROFIT FLIP: %s, resell for +%s (%.0f%%)",
							listing != null ? "buy now" : "cheapest BIN " + HudFormatter.fmt(price),
							HudFormatter.fmt(profit), ppct * 100), 0x55FF55);
				}
			}
			if (!liquid) {
				out.add(HudFormatter.line("    ⚠ only " + (sales == null ? 0 : sales.intValue())
						+ " sales in 24h: slow to resell, value less certain", HudFormatter.YELLOW));
			}
		}

		if (market != null && market.has("flags") && market.get("flags").isJsonArray()) {
			for (JsonElement e : market.getAsJsonArray("flags")) {
				JsonObject fl = e.getAsJsonObject();
				if ("thin".equals(fl.get("code").getAsString()) && !bz) continue;
				boolean severe = fl.get("level").getAsInt() >= 2;
				out.add(HudFormatter.line("    " + (severe ? "⚠ " : "• ") + fl.get("text").getAsString(),
						severe ? HudFormatter.RED : HudFormatter.YELLOW));
			}
		}

		JsonObject f = HudFormatter.obj(d, "forecast", bz ? "buy" : "main");
		JsonObject f24 = HudFormatter.obj(f, "h", "24");
		Double last = HudFormatter.num(f, "last");
		if (f24 != null && last != null && last > 0 && HudFormatter.num(f24, "f") != null) {
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
		return new Result(out, banner);
	}
}
