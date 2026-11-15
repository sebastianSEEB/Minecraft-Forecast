package dev.sebastian.skyprice;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Fallback auction prices from SkyCofl (sky.coflnet.com, free public SkyBlock price history; "Prices provided by
 * SkyCofl"). Used when our own data has no or too little history for an item (no PC collector running, item not
 * traded during the website snapshot). Per item:
 *
 *   GET /api/item/price/{tag}/history/week   [{min, max, avg, volume, time}, …]   → 24 h / 7 d avg, high, low, sold
 *   GET /api/item/price/{tag}/bin            {lowest, uuid, secondLowest}          → lowest BIN, 2nd lowest
 *
 * Pets use tag PET_TYPE with ?Rarity=TIER; enchanted books use ENCHANTMENT_NAME_LEVEL (same as our keys).
 * Requests are spaced 400 ms apart (SkyCofl allows 30 per 10 s) and cached for 10 min (BIN: 3 min).
 * Results are built into a doc with the same shape as our own item docs, so the tooltip, verdict and bid preset work.
 */
final class CoflClient {
	private static final String BASE = "https://sky.coflnet.com/api/item/price/";
	private static final long TTL_MS = 10 * 60_000L, BIN_TTL_MS = 3 * 60_000L;

	private record Entry(long at, JsonArray history, long binAt, JsonObject bin, boolean empty) {}

	private final SkyPriceConfig cfg;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private final Map<String, Entry> cache = new ConcurrentHashMap<>();
	private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
	private final ScheduledExecutorService queue = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "SkyPrice-SkyCofl");
		t.setDaemon(true);
		return t;
	});
	private volatile long nextSlot = 0;
	private volatile long blockedUntil = 0;

	CoflClient(SkyPriceConfig cfg) {
		this.cfg = cfg;
	}

	/** SkyCofl item tag + filter query for one of our keys. */
	static String path(String key) {
		if (key.startsWith("PET_")) {
			int us = key.lastIndexOf('_');
			String tier = key.substring(us + 1);
			for (String t : Valuation.TIERS) {
				if (t.equals(tier)) return key.substring(0, us) + "%s?Rarity=" + tier;
			}
		}
		return URLEncoder.encode(key, StandardCharsets.UTF_8) + "%s";
	}

	private CompletableFuture<JsonElement> get(String url) {
		long now = System.currentTimeMillis();
		if (now < blockedUntil) return CompletableFuture.failedFuture(new IllegalStateException("rate limited"));
		long slot = Math.max(now, nextSlot);
		nextSlot = slot + 400;
		CompletableFuture<JsonElement> f = new CompletableFuture<>();
		queue.schedule(() -> {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
					.header("User-Agent", "SkyPrice-HUD (Fabric mod)").GET().build();
			http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
				if (err != null) { f.completeExceptionally(err); return; }
				if (resp.statusCode() == 429) {
					blockedUntil = System.currentTimeMillis() + 60_000L;
					f.completeExceptionally(new IllegalStateException("429"));
					return;
				}
				if (resp.statusCode() == 404 || resp.statusCode() == 204) { f.complete(null); return; }
				if (resp.statusCode() != 200) { f.completeExceptionally(new IllegalStateException("HTTP " + resp.statusCode())); return; }
				try {
					f.complete(JsonParser.parseString(resp.body()));
				} catch (RuntimeException e) {
					f.completeExceptionally(e);
				}
			});
		}, slot - now, TimeUnit.MILLISECONDS);
		return f;
	}

	/**
	 * SkyCofl-based doc for an auction item, or null while loading / when SkyCofl has nothing.
	 * @param fetch false = only use what's cached (for the many items the yellow-border check looks at)
	 */
	JsonObject doc(String key, boolean fetch) {
		if (cfg == null || !cfg.coflFallback || key == null) return null;
		Entry e = cache.get(key);
		long now = System.currentTimeMillis();
		boolean stale = e == null || now - e.at() > TTL_MS || (e.bin() != null || !e.empty()) && now - e.binAt() > BIN_TTL_MS;
		if (fetch && stale && inFlight.putIfAbsent(key, true) == null) refresh(key, e);
		if (e == null || e.empty()) return null;
		return build(key, e);
	}

	boolean loading(String key) {
		return inFlight.containsKey(key) && !cache.containsKey(key);
	}

	private void refresh(String key, Entry old) {
		String p = path(key);
		long now = System.currentTimeMillis();
		boolean needHistory = old == null || now - old.at() > TTL_MS;
		CompletableFuture<JsonElement> hist = needHistory ? get(BASE + String.format(p, "/history/week"))
				: CompletableFuture.completedFuture(old.history());
		hist.handle((h, err) -> err != null ? (old == null ? null : old.history()) : h)
				.thenCompose(h -> get(BASE + String.format(p, "/bin")).handle((b, err) -> {
					JsonArray ha = h != null && h.isJsonArray() ? h.getAsJsonArray() : null;
					JsonObject bo = b != null && b.isJsonObject() ? b.getAsJsonObject() : (old == null ? null : old.bin());
					boolean empty = (ha == null || ha.isEmpty()) && (bo == null || HudFormatter.num(bo, "lowest") == null
							|| HudFormatter.num(bo, "lowest") <= 0);
					cache.put(key, new Entry(needHistory ? System.currentTimeMillis() : old.at(), ha,
							System.currentTimeMillis(), bo, empty));
					return null;
				}))
				.whenComplete((v, err) -> inFlight.remove(key));
	}

	private static long time(JsonObject o) {
		if (!o.has("time") || o.get("time").isJsonNull()) return 0;
		String t = o.get("time").getAsString();
		try {
			return OffsetDateTime.parse(t).toInstant().toEpochMilli();
		} catch (RuntimeException ignored) {
			// no offset: SkyCofl times are UTC
		}
		try {
			return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli();
		} catch (RuntimeException ignored) {
			return Instant.now().toEpochMilli();
		}
	}

	/** Same shape as our published item docs (summary / stats / market), marked source = SkyCofl. */
	private static JsonObject build(String key, Entry e) {
		long now = System.currentTimeMillis();
		List<double[]> pts = new ArrayList<>();            // [time, min, max, avg, volume]
		if (e.history() != null) {
			for (JsonElement el : e.history()) {
				if (!el.isJsonObject()) continue;
				JsonObject o = el.getAsJsonObject();
				Double avg = HudFormatter.num(o, "avg");
				if (avg == null || avg <= 0) continue;
				Double mn = HudFormatter.num(o, "min"), mx = HudFormatter.num(o, "max"), vol = HudFormatter.num(o, "volume");
				pts.add(new double[]{time(o), mn == null ? avg : mn, mx == null ? avg : mx, avg, vol == null ? 0 : vol});
			}
		}
		JsonObject d24 = window(pts, now - 86_400_000L), d7 = window(pts, 0);
		Double lowest = HudFormatter.num(e.bin(), "lowest"), second = HudFormatter.num(e.bin(), "secondLowest");
		if (lowest != null && lowest <= 0) lowest = null;
		if (second != null && second <= 0) second = null;

		JsonObject summary = new JsonObject();
		if (lowest != null) summary.addProperty("lbin", lowest);
		if (d24 != null) {
			summary.addProperty("sales", d24.get("n").getAsDouble());
			summary.addProperty("avg", d24.get("avg").getAsDouble());
			summary.addProperty("hi", d24.get("hi").getAsDouble());
			summary.addProperty("lo", d24.get("lo").getAsDouble());
		} else {
			summary.addProperty("sales", 0);
		}
		JsonObject stats = new JsonObject();
		if (d24 != null) stats.add("24h", wrap(d24));
		if (d7 != null) stats.add("7d", wrap(d7));

		// fair value: median of 24 h avg, 7 d avg and lowest BIN (whichever exist)
		List<Double> f = new ArrayList<>();
		if (d24 != null) f.add(d24.get("avg").getAsDouble());
		if (d7 != null) f.add(d7.get("avg").getAsDouble());
		if (lowest != null) f.add(lowest);
		JsonObject market = new JsonObject();
		if (!f.isEmpty()) {
			f.sort(Double::compare);
			double fair = f.size() % 2 == 1 ? f.get(f.size() / 2) : (f.get(f.size() / 2 - 1) + f.get(f.size() / 2)) / 2;
			market.addProperty("fair", fair);
			// after buying the cheapest BIN you compete with the 2nd cheapest
			market.addProperty("resale", second != null ? Math.min(fair, second * 0.99) : fair * 0.97);
		}
		if (d24 != null) market.addProperty("sold_median_72h", d24.get("avg").getAsDouble());
		if (d7 != null) market.addProperty("avg_7d", d7.get("avg").getAsDouble());
		JsonObject listed = new JsonObject();
		if (lowest != null) listed.addProperty("lowest", lowest);
		if (second != null) listed.addProperty("second", second);
		market.add("listed", listed);

		JsonObject doc = new JsonObject();
		doc.addProperty("k", key);
		doc.addProperty("kind", "ah");
		doc.addProperty("source", "SkyCofl");
		doc.add("summary", summary);
		doc.add("stats", stats);
		doc.add("market", market);
		return doc;
	}

	private static JsonObject wrap(JsonObject w) {
		JsonObject o = new JsonObject();
		o.add("sold", w);
		return o;
	}

	/** Volume-weighted avg, highest max, lowest min and total sold over points since `from`. */
	private static JsonObject window(List<double[]> pts, long from) {
		double sumPv = 0, sumV = 0, sumAvg = 0, hi = 0, lo = Double.MAX_VALUE, since = Double.MAX_VALUE;
		int n = 0;
		for (double[] p : pts) {
			if (p[0] < from) continue;
			n++;
			sumAvg += p[3];
			sumPv += p[3] * p[4];
			sumV += p[4];
			hi = Math.max(hi, p[2]);
			lo = Math.min(lo, p[1]);
			since = Math.min(since, p[0]);
		}
		if (n == 0) return null;
		JsonObject o = new JsonObject();
		o.addProperty("avg", sumV > 0 ? sumPv / sumV : sumAvg / n);
		o.addProperty("hi", hi);
		o.addProperty("lo", lo);
		o.addProperty("n", sumV);
		o.addProperty("since", since);
		return o;
	}

	/** True when our own doc has too little auction history to be useful (e.g. only the website snapshot). */
	static boolean thin(JsonObject d) {
		if (d == null) return true;
		if (!"ah".equals(HudFormatter.str(d, "kind"))) return false;
		Double sales = HudFormatter.num(HudFormatter.obj(d, "summary"), "sales");
		JsonObject w7 = HudFormatter.obj(d, "stats", "7d", "sold");
		Double n7 = HudFormatter.num(w7, "n");
		return (sales == null || sales < 3) && (n7 == null || n7 < 10);
	}
}
