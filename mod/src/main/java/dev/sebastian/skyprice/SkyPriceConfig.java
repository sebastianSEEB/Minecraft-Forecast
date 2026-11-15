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
	boolean modScan = true;           // scan the AH for your listings + bids every 15 min while you play (FCF panel / ERP)
	boolean coflFallback = true;      // use SkyCofl (sky.coflnet.com) prices when our data has too little history
	boolean fcfButton = true;
	boolean erpButton = true;         // "ERP" button above your inventory: RealGame-style console for your AH trading
	/** Bazaar products shown in the ERP "Bazaar" window and the FCF panel's Bazaar tab (/skyprice bz add|remove <id>). */
	java.util.List<String> bazaarWatch = new java.util.ArrayList<>(java.util.Arrays.asList(Erp.DEFAULT_BAZAAR));         // "FCF" button above your inventory: potential free cash flow panel
	boolean highlightDeals = true;    // yellow border on AH auctions worth buying/bidding (glow when >= 20 % under fair)
	boolean reportPurse = true;       // send your purse (from the sidebar) to the PC collector for the Wallet tab
	int purseReportSeconds = 300;
	boolean dealAlerts = true;        // chat alert for new underpriced BINs found by the PC collector
	double dealMinDiscountPercent = 20;
	double dealMinValue = 1_000_000;
	int dealPollSeconds = 60;   // clicking an auction copies the target bid to your clipboard (you place the bid yourself)
	// ---- flipping (set by the 30-second setup in the FLIP panel; /skyprice setup to redo it)
	boolean setupDone = false;
	boolean simpleMode = true;        // Simple: tooltips show only what to do. Pro: every number (/skyprice mode pro)
	String risk = "normal";           // safe / normal / bold (sets minFlipProfitPercent + minSalesPerDay)
	double budgetPercent = 25;        // most of your purse one flip may use
	double fallbackBudget = 1_000_000;// budget when the purse can't be read from the sidebar
	double minSalesPerDay = 4;        // items that sell less often are never flagged as flips
	double maxHoldHours = 8;          // longest you're willing to wait for a resale
	double minFlipProfitCoins = 20_000;
	boolean flipButton = true;        // "FLIP" tab right of your inventory / chests: top flips that fit your purse
	boolean flipFeed = true;          // the mod's AH scan also looks for underpriced BINs (gap to the next-cheapest copy)
	int scanMinutes = 15;             // how often the mod scans the whole AH while you play (min 5)
	boolean flipAlerts = true;        // chat alert for the best new flips found by the scan (max 3 per scan)
	boolean alertSound = true;        // ping on: item sold, outbid, bazaar order filled / outbid, big new flip
	double bazaarTaxPercent = 1.25;   // 1.25 % default; lower with the Bazaar Flipper account upgrade
	double bazaarSharePercent = 10;   // part of a product's traded volume you can expect to fill
	double bazaarRoundHours = 2;      // one flip round (buy order fills + sell offer sells) should take at most this long
	double bazaarMaxSpreadPercent = 60;// wider spreads are thin / manipulated markets: never suggested
	boolean bazaarOrderTracker = true;// read "Your Bazaar Orders" and warn when an order is outbid / undercut
	boolean profitTracker = true;     // log buys + sells from chat → realized profit per flip (config/skyprice-trades.csv)
	String lastSummaryDay = "";       // the "Yesterday: +X from N flips" message is shown once per day

	boolean showHistory = true;      // 7d / 30d / all-time lines
	boolean showForecast = true;
	boolean showSeasons = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Coins one flip may use: budgetPercent of your purse, or fallbackBudget if the purse is unknown. */
	double budget(Double purse) {
		return purse != null && purse > 0 ? purse * budgetPercent / 100.0 : fallbackBudget;
	}

	void applyRisk(String level) {
		risk = level;
		switch (level) {
			case "safe" -> { minFlipProfitPercent = 10; minSalesPerDay = 10; }
			case "bold" -> { minFlipProfitPercent = 3; minSalesPerDay = 1; }
			default -> { minFlipProfitPercent = 5; minSalesPerDay = 4; risk = "normal"; }
		}
	}

	void save() {
		try {
			Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("skyprice.json"), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			System.err.println("[SkyPrice] could not save config: " + e);
		}
	}

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
