package dev.sebastian.skyprice;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Builds the tooltip / chat lines from an item document (see market/detail.py for the format). */
final class HudFormatter {
	static final int GOLD = 0xFFAA00, AQUA = 0x55FFFF, GRAY = 0xAAAAAA, WHITE = 0xFFFFFF, GREEN = 0x55FF55,
			RED = 0xFF5555, YELLOW = 0xFFFF55, DARK = 0x777777, PURPLE = 0xFF55FF;
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);

	private HudFormatter() {}

	// ------------------------------------------------------------------ helpers
	static String fmt(Double v) {
		if (v == null || v.isNaN()) return "-";
		double a = Math.abs(v);
		String[] suf = {"T", "B", "M", "k"};
		double[] div = {1e12, 1e9, 1e6, 1e3};
		for (int i = 0; i < div.length; i++) {
			if (a >= div[i] * 0.9995) {
				double x = v / div[i];
				String s = Math.abs(x) >= 100 ? String.format(Locale.ROOT, "%.0f", x)
						: Math.abs(x) >= 10 ? String.format(Locale.ROOT, "%.1f", x) : String.format(Locale.ROOT, "%.2f", x);
				return strip(s) + suf[i];
			}
		}
		if (a >= 100) return String.format(Locale.ROOT, "%,.0f", v);
		return strip(String.format(Locale.ROOT, a >= 1 ? "%.1f" : "%.2f", v));
	}

	private static String strip(String s) {
		if (!s.contains(".")) return s;
		s = s.replaceAll("0+$", "");
		return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
	}

	static Double num(JsonObject o, String k) {
		if (o == null || !o.has(k)) return null;
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? null : e.getAsDouble();
	}

	static JsonObject obj(JsonObject o, String... path) {
		JsonObject cur = o;
		for (String p : path) {
			if (cur == null || !cur.has(p) || !cur.get(p).isJsonObject()) return null;
			cur = cur.getAsJsonObject(p);
		}
		return cur;
	}

	static MutableComponent seg(String t, int color) {
		return Component.literal(t).withColor(color);
	}

	static MutableComponent line(Object... parts) {           // alternating text, color
		MutableComponent c = Component.empty();
		for (int i = 0; i + 1 < parts.length; i += 2) c.append(seg((String) parts[i], (Integer) parts[i + 1]));
		return c;
	}

	static MutableComponent change(Double to, Double from) {
		if (to == null || from == null || from == 0) return Component.empty();
		double p = (to / from - 1) * 100;
		if (Math.abs(p) < 0.05) return seg(" ±0%", GRAY);
		return seg(String.format(Locale.ROOT, " %s%.1f%%", p > 0 ? "▲" : "▼", Math.abs(p)), p > 0 ? GREEN : RED);
	}

	private static String day(Double ms) {
		return ms == null ? "" : DAY.format(Instant.ofEpochMilli(ms.longValue()).atZone(ZoneId.systemDefault()));
	}

	private static String ago(long ms) {
		long m = Math.max(0, (System.currentTimeMillis() - ms) / 60000);
		return m < 2 ? "live" : m < 120 ? m + " min ago" : (m / 60) + " h ago";
	}

	// ------------------------------------------------------------------ tooltip
	static List<Component> tooltip(JsonObject d, DataClient.Live live, SkyPriceConfig cfg) {
		return render(d, live, cfg, null).lines();
	}

	/** Tooltip lines + optional "PROFIT FLIP / WORTH BIDDING" banner. @param listing the hovered auction, or null */
	static Verdict.Result render(JsonObject d, DataClient.Live live, SkyPriceConfig cfg, Verdict.Listing listing) {
		List<Component> out = new ArrayList<>();
		Component banner = null;
		Verdict.Result verdict = null;
		JsonObject s = obj(d, "summary");
		boolean bz = "bz".equals(d.get("kind").getAsString());
		JsonObject stats = obj(d, "stats");

		if (bz) {
			Double buy = live != null ? Double.valueOf(live.buy()) : num(s, "now");
			Double sell = live != null ? Double.valueOf(live.sell()) : num(s, "sell");
			long ts = live != null ? live.ts() : (num(s, "ts") != null ? num(s, "ts").longValue() : 0);
			out.add(line("Bazaar ", GOLD, "buy ", GRAY, fmt(buy), WHITE, "  sell ", GRAY, fmt(sell), WHITE,
					ts > 0 ? "  (" + ago(ts) + ")" : "", DARK));
			JsonObject b24 = obj(stats, "24h", "buy");
			if (b24 != null) out.add(line(" 24h  avg ", GRAY, fmt(num(b24, "avg")), WHITE, "  high ", GRAY, fmt(num(b24, "hi")), GREEN,
					"  low ", GRAY, fmt(num(b24, "lo")), RED));
			if (cfg.showVerdict) {
				Verdict.Result v = Verdict.evaluate(d, null, buy, cfg);
				out.addAll(v.lines());
				banner = v.banner();
			}
			if (cfg.showHistory) history(out, stats, "buy");
			if (cfg.showForecast) forecast(out, obj(d, "forecast", "buy"), buy);
		} else {
			Double lbin = num(s, "lbin");
			Double listed = num(s, "listed");
			out.add(line("Auction House ", GOLD, "lowest BIN ", GRAY, fmt(lbin), YELLOW,
					listed != null ? "  (" + listed.intValue() + " listed)" : "", DARK));
			JsonObject a24 = obj(stats, "24h", "sold");
			Double sales = num(s, "sales");
			if (a24 != null && sales != null && sales > 0) {
				out.add(line(" 24h  avg sold ", GRAY, fmt(num(s, "avg")), WHITE, "  high ", GRAY, fmt(num(s, "hi")), GREEN,
						"  low ", GRAY, fmt(num(s, "lo")), RED, "  (" + sales.intValue() + " sales)", DARK));
			} else {
				out.add(line(" No sales in the last 24h", GRAY));
			}
			if (cfg.showVerdict) {
				Verdict.Result v = Verdict.evaluate(d, listing, null, cfg);
				out.addAll(v.lines());
				banner = v.banner();
				verdict = v;
			}
			if (cfg.showHistory) history(out, stats, obj(stats, "all", "sold") != null ? "sold" : "lbin");
			if (cfg.showForecast) forecast(out, obj(d, "forecast", "main"), lbin != null ? lbin : num(s, "avg"));
		}
		if (cfg.showSeasons) seasons(out, d);
		return verdict == null ? new Verdict.Result(out, banner)
				: new Verdict.Result(out, banner, verdict.suggestedBid(), verdict.currentBid(), verdict.maxBid(), verdict.lowestBin(),
						verdict.nextBid(), verdict.bidCap());
	}

	private static void history(List<Component> out, JsonObject stats, String side) {
		JsonObject w7 = obj(stats, "7d", side), w30 = obj(stats, "30d", side), all = obj(stats, "all", side);
		if (w7 != null || w30 != null) {
			out.add(line(" 7d avg ", GRAY, fmt(num(w7, "avg")), WHITE, "  30d avg ", GRAY, fmt(num(w30, "avg")), WHITE));
		}
		if (all != null) {
			out.add(line(" All-time  high ", GRAY, fmt(num(all, "hi")), GREEN, " (" + day(num(all, "hi_at")) + ")", DARK,
					"  low ", GRAY, fmt(num(all, "lo")), RED, " (" + day(num(all, "lo_at")) + ")", DARK,
					"  since " + day(num(all, "since")), DARK));
		}
	}

	private static void forecast(List<Component> out, JsonObject f, Double base) {
		if (f == null) return;
		if (!f.has("available") || !f.get("available").getAsBoolean()) {
			Double have = num(f, "hours_of_data"), need = num(f, "needs_hours");
			out.add(line(" Forecast: ", AQUA, "collecting data (" + (have == null ? 0 : have.intValue()) + "/"
					+ (need == null ? 12 : need.intValue()) + " h)", GRAY));
			return;
		}
		JsonObject h = obj(f, "h");
		Double last = num(f, "last");
		Double ref = last != null ? last : base;
		MutableComponent l = line(" Forecast ", AQUA);
		for (String k : new String[]{"1", "6", "24"}) {
			JsonObject x = obj(h, k);
			if (x == null) continue;
			l.append(seg(k + "h ", GRAY)).append(seg(fmt(num(x, "f")), WHITE)).append(change(num(x, "f"), ref)).append(seg("  ", GRAY));
		}
		out.add(l);
		JsonObject far = obj(h, "24");
		if (far != null) {
			out.add(line(" 80% range 24h: " + fmt(num(far, "lo")) + " – " + fmt(num(far, "hi")), GRAY,
					"  [" + far.get("model").getAsString() + ", MAPE "
							+ (num(far, "mape") == null ? "-" : String.format(Locale.ROOT, "%.1f", num(far, "mape"))) + "%]", DARK));
		}
		if (f.has("biased") && f.get("biased").getAsBoolean()) out.add(line(" ⚠ 1h model biased", YELLOW));
	}

	/** Confirmed cycles, soonest peak first. */
	private static void seasons(List<Component> out, JsonObject d) {
		if (!d.has("seasons") || !d.get("seasons").isJsonArray()) return;
		JsonArray arr = d.getAsJsonArray("seasons");
		JsonObject best = null;
		long bestAt = Long.MAX_VALUE;
		for (JsonElement e : arr) {
			JsonObject s = e.getAsJsonObject();
			if (!s.get("confirmed").getAsBoolean()) continue;
			long at = nextAt(num(s, "peak_at"), num(s, "period"));
			if (at < bestAt) { bestAt = at; best = s; }
		}
		if (best == null) return;
		long dip = nextAt(num(best, "trough_at"), num(best, "period"));
		out.add(line(" Season ", PURPLE, best.get("label").getAsString() + " cycle: ", GRAY,
				"peak " + until(bestAt), WHITE, String.format(Locale.ROOT, " (%+.1f%%)", num(best, "rise_to_peak") * 100), GREEN,
				"  dip " + until(dip), WHITE, String.format(Locale.ROOT, " (%+.1f%%)", num(best, "fall_to_trough") * 100), RED));
	}

	static long nextAt(Double t, Double periodH) {
		if (t == null || periodH == null) return Long.MAX_VALUE;
		long x = t.longValue(), p = (long) (periodH * 3_600_000L), now = System.currentTimeMillis();
		while (x < now - 3_600_000L) x += p;
		return x;
	}

	static String until(long at) {
		double h = (at - System.currentTimeMillis()) / 3_600_000.0;
		if (h < 1) return "now";
		if (h < 48) return "in " + Math.round(h) + "h";
		return String.format(Locale.ROOT, "in %.1fd", h / 24);
	}

	// ------------------------------------------------------------------ chat (/skyprice)
	static List<Component> chat(String key, JsonObject d, DataClient.Live live, SkyPriceConfig cfg, String source) {
		List<Component> out = new ArrayList<>();
		out.add(line("━━ ", DARK, key, GOLD, "  (" + source + ")", DARK));
		SkyPriceConfig all = new SkyPriceConfig();
		all.showForecast = all.showHistory = all.showSeasons = true;
		out.addAll(tooltip(d, live, all));
		JsonObject daily = obj(d, "daily");
		boolean bz = "bz".equals(d.get("kind").getAsString());
		String series = bz ? "buy" : "median";
		if (daily != null && daily.has(series) && daily.get(series).isJsonArray()) {
			JsonArray a = daily.getAsJsonArray(series);
			long start = daily.get("start").isJsonNull() ? 0 : daily.get("start").getAsLong();
			int from = Math.max(0, a.size() - 14);
			MutableComponent l = line(" Daily " + (bz ? "buy" : "median sale") + " (last " + (a.size() - from) + " days): ", GRAY);
			for (int i = from; i < a.size(); i++) {
				JsonElement v = a.get(i);
				String when = DAY.format(Instant.ofEpochMilli(start + i * 86_400_000L).atZone(ZoneId.systemDefault()));
				l.append(seg(when + " ", DARK)).append(seg(v.isJsonNull() ? "-" : fmt(v.getAsDouble()), WHITE)).append(seg(i < a.size() - 1 ? " · " : "", DARK));
			}
			out.add(l);
		}
		if (d.has("seasons")) {
			for (JsonElement e : d.getAsJsonArray("seasons")) {
				JsonObject s = e.getAsJsonObject();
				long peak = nextAt(num(s, "peak_at"), num(s, "period"));
				out.add(line(" " + s.get("label").getAsString() + " cycle: ", GRAY,
						s.get("confirmed").getAsBoolean() ? "confirmed" : "not confirmed", s.get("confirmed").getAsBoolean() ? GREEN : DARK,
						String.format(Locale.ROOT, "  swing %.1f%%, next peak %s (%s)", num(s, "amplitude") * 100, until(peak),
								WHEN.format(Instant.ofEpochMilli(peak).atZone(ZoneId.systemDefault()))), GRAY));
			}
		}
		out.add(line(" Charts: sebastianseeb.github.io/Minecraft-Forecast/#item=" + key, DARK));
		return out;
	}
}
