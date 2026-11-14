package dev.sebastian.skyprice;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Fetches tooltip lines from the local server without ever blocking the game:
 * the tooltip shows the cached lines (or "loading…") and a background request refreshes them.
 */
final class PriceClient {
	private static final int GRAY = 0xAAAAAA;
	private static final List<Component> LOADING =
			List.of(Component.literal("Market: loading…").withColor(GRAY));
	private static final List<Component> OFFLINE =
			List.of(Component.literal("Market: price server offline (start run.py)").withColor(0x777777));

	private record Entry(long fetchedAt, List<Component> lines) {}

	private final SkyPriceConfig config;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private final Map<String, Entry> cache = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private volatile long offlineUntil = 0;

	PriceClient(SkyPriceConfig config) {
		this.config = config;
	}

	List<Component> lines(String key) {
		long now = System.currentTimeMillis();
		Entry e = cache.get(key);
		boolean stale = e == null || now - e.fetchedAt > config.refreshSeconds * 1000L;
		if (stale && now >= offlineUntil && inFlight.add(key)) {
			fetch(key);
		}
		if (e != null) return e.lines;
		if (now < offlineUntil) return config.showOfflineHint ? OFFLINE : List.of();
		return LOADING;
	}

	private void fetch(String key) {
		String url = "http://" + config.host + ":" + config.port + "/hud/"
				+ URLEncoder.encode(key, StandardCharsets.UTF_8);
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			try {
				if (err != null || resp.statusCode() != 200) {
					offlineUntil = System.currentTimeMillis() + 10_000;   // retry in 10 s
					return;
				}
				cache.put(key, new Entry(System.currentTimeMillis(), parse(resp.body())));
				if (cache.size() > 2000) cache.clear();
			} catch (RuntimeException ex) {
				cache.put(key, new Entry(System.currentTimeMillis(), List.of()));
			} finally {
				inFlight.remove(key);
			}
		});
	}

	/** {"lines": [[{"t": "text", "c": 0xRRGGBB}, ...], ...]} -> tooltip lines */
	private static List<Component> parse(String body) {
		JsonObject root = JsonParser.parseString(body).getAsJsonObject();
		JsonArray lines = root.getAsJsonArray("lines");
		List<Component> out = new ArrayList<>();
		for (JsonElement lineEl : lines) {
			MutableComponent line = Component.empty();
			for (JsonElement segEl : lineEl.getAsJsonArray()) {
				JsonObject seg = segEl.getAsJsonObject();
				line.append(Component.literal(seg.get("t").getAsString()).withColor(seg.get("c").getAsInt()));
			}
			out.add(line);
		}
		return List.copyOf(out);
	}
}
