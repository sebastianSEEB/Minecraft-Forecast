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

	boolean valueUpgrades = true;    // value each copy: enchants, stars, recomb (rarity), potato books, gems, pet level/rarity/item/skin
	boolean showVerdict = true;      // "GOOD BUY / FAIR PRICE / OVERPRICED" line under the average
	boolean showFlipBanner = true;   // bold "PROFIT FLIP / WORTH BIDDING" line right under the item name
	double minFlipProfitPercent = 5; // profit after AH fees needed before an item is marked as a flip
	double maxBidPercentOfLowestBin = 70; // preset bid = lowest valid bid (current + 2.5 %), never above this % of lowest BIN
	boolean copyBidOnClick = true;
	boolean watchBids = true;         // auctions you click (bid copied) go on a watch list
	int watchRecheckSeconds = 30;     // how often the watch list re-checks lowest BIN
	int watchHours = 48;              // watched auctions expire after this

	boolean outbidHud = true;         // panel right of your inventory with auctions you've been outbid on (click = open)
	int outbidHours = 12;
	boolean fcfButton = true;         // "FCF" button above your inventory: potential free cash flow panel
	boolean highlightDeals = true;    // yellow border on AH auctions worth buying/bidding (glow when >= 20 % under fair)
	boolean reportPurse = true;       // send your purse (from the sidebar) to the PC collector for the Wallet tab
	int purseReportSeconds = 300;
	boolean dealAlerts = true;        // chat alert for new underpriced BINs found by the PC collector
	double dealMinDiscountPercent = 20;
	double dealMinValue = 1_000_000;
	int dealPollSeconds = 60;   // clicking an auction copies the target bid to your clipboard (you place the bid yourself)
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
