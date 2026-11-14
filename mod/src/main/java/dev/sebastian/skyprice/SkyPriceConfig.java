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
	String host = "127.0.0.1";
	int port = 8765;
	int refreshSeconds = 30;
	boolean showOfflineHint = true;

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
