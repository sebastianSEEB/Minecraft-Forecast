package dev.sebastian.skyprice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** .minecraft/config/skyprice.json — created with defaults on first launch. */
final class SkyPriceConfig {
	boolean enabled = true;

	/** Your PC collector (run.py). Used first when it is running: freshest data. */
	String localServer = "http://127.0.0.1:8765";
	/** Published history + forecasts (GitHub Pages). Used when the PC collector isn't reachable. */
	String dataUrl = "https://sebastianseeb.github.io/Minecraft-Forecast/data";
	/** Live bazaar prices straight from the public Hypixel API (no key needed). */
	boolean useHypixelApi = true;
	String hypixelBazaarUrl = "https://api.hypixel.net/v2/skyblock/bazaar";

	int localRefreshSeconds = 30;
	int publishedRefreshSeconds = 600;
	int liveBazaarRefreshSeconds = 60;

	boolean showVerdict = true;      // "GOOD BUY / FAIR PRICE / OVERPRICED" line under the average
	boolean showHistory = true;      // 7d / 30d / all-time lines
	boolean showForecast = true;
	boolean showSeasons = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	static SkyPriceConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("skyprice.json");
		SkyPriceConfig cfg = new SkyPriceConfig();
		try {
			if (Files.exists(file)) {
				SkyPriceConfig read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SkyPriceConfig.class);
				if (read != null) cfg = read;
			}
			Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			System.err.println("[SkyPrice] could not read config, using defaults: " + e);
		}
		return cfg;
	}
}
