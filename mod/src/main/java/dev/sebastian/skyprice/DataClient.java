package dev.sebastian.skyprice;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Where the numbers come from, in order:
 *   1. local collector  GET {localServer}/itemdata/KEY        (if run.py is running)
 *   2. published data   GET {dataUrl}/items/KEY.json           (GitHub Pages: full history, forecasts, seasons)
 *   3. Hypixel API      GET api.hypixel.net/v2/skyblock/bazaar (live bazaar buy/sell, refreshed every minute)
 * Nothing ever blocks the game: callers get whatever is cached and a background refresh starts.
 */
final class DataClient {
	record Doc(long fetchedAt, JsonObject json, String source) {}
	record Live(double buy, double sell, long ts) {}

	private final SkyPriceConfig cfg;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private final Map<String, Doc> docs = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private volatile long localDownUntil = 0;
	private volatile long remoteDownUntil = 0;

	private volatile JsonObject components;
	private volatile long componentsFetchedAt = 0;
	private volatile boolean componentsLoading = false;

	private volatile Map<String, Live> bazaar = Map.of();
	private volatile long bazaarFetchedAt = 0;
	private volatile boolean bazaarLoading = false;

	DataClient(SkyPriceConfig cfg) {
		this.cfg = cfg;
	}

	/** Cached document for the key (may be null while loading). Starts a refresh if stale. */
	Doc doc(String key) {
		long now = System.currentTimeMillis();
		Doc d = docs.get(key);
		long maxAge = 1000L * (d != null && "local".equals(d.source) ? cfg.localRefreshSeconds : cfg.publishedRefreshSeconds);
		if ((d == null || now - d.fetchedAt > maxAge) && inFlight.add(key)) {
			fetchDoc(key).whenComplete((res, err) -> {
				if (res != null) docs.put(key, res);
				else if (d == null) docs.put(key, new Doc(System.currentTimeMillis(), null, "none"));
				if (docs.size() > 3000) docs.clear();
				inFlight.remove(key);
			});
		}
		return d;
	}

	/** Same as doc() but waits for the network (for the chat command, never on the render thread). */
	CompletableFuture<Doc> docAsync(String key) {
		return fetchDoc(key).thenApply(res -> {
			if (res != null) docs.put(key, res);
			return res;
		});
	}

	/** Fresh lowest BIN for a few items from the PC collector (re-checked every 30 s). Null if it isn't running. */
	CompletableFuture<JsonObject> localLbin(java.util.List<String> keys) {
		if (cfg.localServer == null || cfg.localServer.isBlank() || keys.isEmpty())
			return CompletableFuture.completedFuture(null);
		String q = URLEncoder.encode(String.join(",", keys), StandardCharsets.UTF_8);
		return getJson(cfg.localServer + "/lbin?keys=" + q, 3).handle((j, e) -> e != null ? null : j);
	}

	/** Underpriced BINs from the PC collector's last full AH scan. Null if it isn't running. */
	CompletableFuture<com.google.gson.JsonArray> localDeals() {
		if (cfg.localServer == null || cfg.localServer.isBlank()) return CompletableFuture.completedFuture(null);
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.localServer + "/deals")).timeout(Duration.ofSeconds(3)).GET().build();
		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((resp, e) -> {
			if (e != null || resp.statusCode() != 200) return null;
			JsonElement el = JsonParser.parseString(resp.body());
			return el.isJsonArray() ? el.getAsJsonArray() : null;
		});
	}

	/**
	 * Upgrade prices + valuation constants (market/valuation.py components_doc): local collector /components,
	 * else {dataUrl}/components.json. Refreshed every 10 min; null until the first fetch completes.
	 */
	JsonObject components() {
		long now = System.currentTimeMillis();
		if (now - componentsFetchedAt > (components == null ? 30_000L : 600_000L) && !componentsLoading) {
			componentsLoading = true;
			CompletableFuture<JsonObject> local = (cfg.localServer == null || cfg.localServer.isBlank())
					? CompletableFuture.completedFuture(null)
					: getJson(cfg.localServer + "/components", 3).handle((j, e) -> e != null ? null : j);
			local.thenCompose(j -> j != null || cfg.dataUrl == null || cfg.dataUrl.isBlank() ? CompletableFuture.completedFuture(j)
							: getJson(cfg.dataUrl + "/components.json", 15).handle((r, e) -> e != null ? null : r))
					.whenComplete((j, e) -> {
						if (j != null) components = j;
						componentsFetchedAt = System.currentTimeMillis();
						componentsLoading = false;
					});
		}
		return components;
	}

	/** Wallet + potential FCF report: local collector /wallet, else the published {dataUrl}/wallet.json. */
	CompletableFuture<Doc> walletReport() {
		CompletableFuture<JsonObject> local = (cfg.localServer == null || cfg.localServer.isBlank())
				? CompletableFuture.completedFuture(null)
				: getJson(cfg.localServer + "/wallet", 3).handle((j, e) -> e != null || j == null || j.entrySet().isEmpty() ? null : j);
		return local.thenCompose(j -> {
			if (j != null) return CompletableFuture.completedFuture(new Doc(System.currentTimeMillis(), j, "your PC"));
			if (cfg.dataUrl == null || cfg.dataUrl.isBlank()) return CompletableFuture.completedFuture(null);
			// positions.json: GitHub scans the AH for you every 15 min; wallet.json: published by your PC collector
			return getJson(cfg.dataUrl + "/positions.json", 10).handle((r, e) -> e != null ? null : r).thenCompose(r -> {
				if (r != null && r.has("fcf")) return CompletableFuture.completedFuture(new Doc(System.currentTimeMillis(), r, "website"));
				return getJson(cfg.dataUrl + "/wallet.json", 10).handle((w, e) ->
						e != null || w == null ? null : new Doc(System.currentTimeMillis(), w, "website"));
			});
		});
	}

	private volatile JsonObject bazaarWatch;
	private volatile long bazaarWatchAt;

	/** {"items": {ID: [[ts, buy, sell], …]}} recorded every 15 min by the GitHub AH job (7 days). Null until loaded. */
	JsonObject bazaarHistory() {
		long now = System.currentTimeMillis();
		if (now - bazaarWatchAt > 300_000L && cfg.dataUrl != null && !cfg.dataUrl.isBlank()) {
			bazaarWatchAt = now;
			getJson(cfg.dataUrl + "/bazaar_watch.json", 10).whenComplete((j, e) -> {
				if (j != null) bazaarWatch = j;
			});
		}
		return bazaarWatch;
	}

	boolean isLoading(String key) {
		return inFlight.contains(key);
	}

	private CompletableFuture<Doc> fetchDoc(String key) {
		String enc = URLEncoder.encode(key, StandardCharsets.UTF_8);
		long now = System.currentTimeMillis();
		CompletableFuture<Doc> local = (cfg.localServer == null || cfg.localServer.isBlank() || now < localDownUntil)
				? CompletableFuture.completedFuture(null)
				: getJson(cfg.localServer + "/itemdata/" + enc, 3).handle((j, e) -> {
					if (e != null) { localDownUntil = System.currentTimeMillis() + 30_000; return null; }
					return j == null ? null : new Doc(System.currentTimeMillis(), j, "local");
				});
		return local.thenCompose(d -> {
			if (d != null) return CompletableFuture.completedFuture(d);
			if (cfg.dataUrl == null || cfg.dataUrl.isBlank() || System.currentTimeMillis() < remoteDownUntil)
				return CompletableFuture.completedFuture(null);
			String file = key.replace(":", "__");
			return getJson(cfg.dataUrl + "/items/" + URLEncoder.encode(file, StandardCharsets.UTF_8) + ".json", 8).handle((j, e) -> {
				if (e != null) { remoteDownUntil = System.currentTimeMillis() + 60_000; return null; }
				return j == null ? null : new Doc(System.currentTimeMillis(), j, "published");
			});
		});
	}

	/** Completes with the parsed object, null on 404, exceptionally on network errors. */
	private CompletableFuture<JsonObject> getJson(String url, int timeoutSeconds) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds))
				.header("User-Agent", "SkyPrice-HUD/1.0").GET().build();
		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
			if (resp.statusCode() == 404) return null;
			if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
			JsonElement el = JsonParser.parseString(resp.body());
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		});
	}

	/** Live bazaar quick_status for a product (null if unknown / not loaded yet). */
	Live live(String product) {
		if (!cfg.useHypixelApi) return null;
		long now = System.currentTimeMillis();
		if (now - bazaarFetchedAt > cfg.liveBazaarRefreshSeconds * 1000L && !bazaarLoading) {
			bazaarLoading = true;
			HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.hypixelBazaarUrl)).timeout(Duration.ofSeconds(15))
					.header("User-Agent", "SkyPrice-HUD/1.0").header("Accept-Encoding", "gzip").GET().build();
			http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).whenComplete((resp, err) -> {
				try {
					if (err == null && resp.statusCode() == 200) {
						boolean gz = resp.headers().firstValue("Content-Encoding").map(v -> v.contains("gzip")).orElse(false);
						try (InputStream in = gz ? new GZIPInputStream(resp.body()) : resp.body()) {
							bazaar = parseBazaar(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
						}
					}
				} catch (Exception ignored) {
					// keep the previous snapshot
				} finally {
					bazaarFetchedAt = System.currentTimeMillis();
					bazaarLoading = false;
				}
			});
		}
		return bazaar.get(product);
	}

	private static Map<String, Live> parseBazaar(JsonObject root) {
		long ts = root.has("lastUpdated") ? root.get("lastUpdated").getAsLong() : System.currentTimeMillis();
		JsonObject products = root.getAsJsonObject("products");
		Map<String, Live> out = new ConcurrentHashMap<>();
		for (Map.Entry<String, JsonElement> e : products.entrySet()) {
			JsonObject q = e.getValue().getAsJsonObject().getAsJsonObject("quick_status");
			if (q == null) continue;
			out.put(e.getKey(), new Live(q.get("buyPrice").getAsDouble(), q.get("sellPrice").getAsDouble(), ts));
		}
		return out;
	}
}
