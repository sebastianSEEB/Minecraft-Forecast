package dev.sebastian.skyprice;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Price forecasts for the flip lists, from your collector's data (market/forecast.py, EXC 3650 lectures 4–5):
 * local collector /forecasts, else the published {dataUrl}/forecasts.json. Every item with enough history has a
 * 1h / 6h / 24h forecast per side (bazaar: buy = instant-buy price your sell offer competes with, sell = top buy
 * order; AH: main = hourly median sale or lowest BIN). The set grows by itself as the collector gathers more hours.
 *
 * How a flip uses it (only when forecasting is on, see {@link SkyPriceConfig#forecastFlips}):
 *   change at T  = forecast at the time you'll sell (interpolated between now, 1h, 6h, 24h) ÷ price now − 1
 *   counted      = the whole change when it's DOWN (a falling price eats your margin);
 *                  only the part beyond the model's typical error (MAPE at T) when it's UP;
 *                  nothing when the model is biased (|tracking signal| over the limit).
 * Beyond 24h the 24h forecast is used (lecture: near-term forecasts are more accurate).
 */
final class Forecasts {
	/** One series' forecast: f/mape/lo/hi at 1h, 6h, 24h. */
	record Side(double last, double[] f, double[] mape, boolean biased, boolean trend, String randomness, boolean seasonal,
			String model) {}

	static final double[] H = {1, 6, 24};

	private Forecasts() {}

	/** Parse forecasts.json → key → side ("buy" / "sell" / "main") → Side. */
	static Map<String, Map<String, Side>> parse(JsonObject root) {
		Map<String, Map<String, Side>> out = new HashMap<>();
		if (root == null || !root.has("items") || !root.get("items").isJsonObject()) return out;
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("items").entrySet()) {
			if (!e.getValue().isJsonObject()) continue;
			Map<String, Side> sides = new HashMap<>();
			for (Map.Entry<String, JsonElement> s : e.getValue().getAsJsonObject().entrySet()) {
				try {
					JsonObject o = s.getValue().getAsJsonObject();
					sides.put(s.getKey(), new Side(o.get("last").getAsDouble(), arr(o, "f"), arr(o, "mape"),
							o.has("biased") && o.get("biased").getAsBoolean(), o.has("trend") && o.get("trend").getAsBoolean(),
							o.has("rnd") && !o.get("rnd").isJsonNull() ? o.get("rnd").getAsString() : "?",
							o.has("seas") && o.get("seas").getAsBoolean(), o.has("m") ? o.get("m").getAsString() : ""));
				} catch (RuntimeException ignored) {
					// skip a malformed side
				}
			}
			if (!sides.isEmpty()) out.put(e.getKey(), sides);
		}
		return out;
	}

	private static double[] arr(JsonObject o, String k) {
		JsonArray a = o.getAsJsonArray(k);
		double[] v = new double[3];
		for (int i = 0; i < 3; i++) v[i] = a.get(i).isJsonNull() ? Double.NaN : a.get(i).getAsDouble();
		return v;
	}

	/** Forecast change from now to `hours` ahead (−0.03 = 3 % lower), linear between now / 1h / 6h / 24h. NaN = none. */
	static double change(Side s, double hours) {
		if (s == null || !(s.last() > 0)) return Double.NaN;
		double v = interp(s.last(), s.f(), hours);
		return Double.isNaN(v) ? Double.NaN : v / s.last() - 1;
	}

	/** Typical error (MAPE, as a fraction) at that horizon. */
	static double error(Side s, double hours) {
		if (s == null) return Double.NaN;
		double v = interp(s.mape()[0], s.mape(), hours);
		return v / 100.0;
	}

	private static double interp(double at0, double[] v, double hours) {
		double h = Math.max(0, Math.min(24, Double.isNaN(hours) || Double.isInfinite(hours) ? 24 : hours));
		if (h <= H[0]) return at0 + (v[0] - at0) * h / H[0];
		for (int i = 1; i < 3; i++) {
			if (h <= H[i]) return v[i - 1] + (v[i] - v[i - 1]) * (h - H[i - 1]) / (H[i] - H[i - 1]);
		}
		return v[2];
	}

	/**
	 * The part of the forecast a flip should count (see the class doc): downside fully, upside only beyond the typical
	 * error, nothing from a biased model. 0 when there is no forecast.
	 */
	static double counted(Side s, double hours) {
		double c = change(s, hours);
		if (Double.isNaN(c) || s.biased()) return 0;
		if (c < 0) return Math.max(-0.5, c);
		double err = error(s, hours);
		double up = c - (Double.isNaN(err) ? c : err);
		return up > 1e-6 ? up : 0;
	}

	/** " fc −3%" / " fc +2%" for the flip rows; "" when it changes nothing worth showing. */
	static String tag(double counted) {
		if (Math.abs(counted) < 0.005) return "";
		return String.format(Locale.ROOT, " · fc %s%.0f%%", counted > 0 ? "+" : "−", Math.abs(counted) * 100);
	}

	/** "Holt … · trend, medium randomness" for chat/tooltips. */
	static String describe(Side s) {
		if (s == null) return "no forecast yet (needs more collected hours)";
		return s.model() + " · " + (s.trend() ? "trend" : "level") + ", " + s.randomness() + " randomness"
				+ (s.seasonal() ? ", hour-of-day season" : "") + (s.biased() ? " · ⚠ biased, ignored" : "");
	}
}
