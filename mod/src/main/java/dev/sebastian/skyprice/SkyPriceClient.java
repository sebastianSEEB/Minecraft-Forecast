package dev.sebastian.skyprice;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * SkyPrice HUD: adds market data to Hypixel SkyBlock item tooltips and a /skyprice command.
 * Data: your PC collector if running, otherwise the published history on GitHub Pages, plus
 * live bazaar prices from the public Hypixel API. Read-only: never sends anything to Hypixel's
 * game server and never automates anything.
 */
public final class SkyPriceClient implements ClientModInitializer {
	private static SkyPriceConfig config;
	private static DataClient data;
	static CoflClient cofl;
	private static final List<Component> LOADING = List.of(HudFormatter.line("Market: loading…", HudFormatter.GRAY));

	@Override
	public void onInitializeClient() {
		config = SkyPriceConfig.load();
		data = new DataClient(config);
		cofl = new CoflClient(config);
		Valuation.init(data);

		ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipFlag, lines) -> {
			try {
				if (!config.enabled) return;
				String key = ItemKeys.keyFor(stack);
				boolean inBazaar = inBazaarMenu();
				if (key == null && inBazaar) key = Bazaar.idForName(stack.getHoverName().getString(), data);   // bazaar menu icons
				if (key == null) return;                       // not a SkyBlock item
				// yours: your own listing (Manage Auctions / Seller: you), or any item that isn't an auction listing
				Verdict.Mine mine = Verdict.ownListing(lines, stack.getCount(), playerName());
				Verdict.Listing listing = mine == null ? Verdict.listing(lines, stack.getCount()) : null;
				if (mine == null && listing == null && !inBazaar) mine = Verdict.Mine.OWNED;
				Verdict.Result r = resultFor(key, listing, config.valueUpgrades ? Valuation.parse(stack) : null, mine);
				if (r == null || r.lines().isEmpty()) return;
				if (mine != null && r.listPrice() != null) BidHelper.hoveredList(key, r.listPrice());
				if (config.showFlipBanner && r.banner() != null && !lines.isEmpty()) lines.add(1, r.banner());   // under the item name
				if (r.nextBid() != null && r.bidCap() != null && r.maxBid() != null)
					BidHelper.hovered(key, r.suggestedBid(), r.nextBid(), r.bidCap(), r.maxBid(), r.lowestBin());
				lines.add(Component.empty());
				lines.addAll(r.lines());
			} catch (RuntimeException e) {          // a SkyPrice bug must never crash the game
				System.err.println("[SkyPrice] tooltip: " + e);
			}
		});

		Highlight.init(config, data);
		OutbidHud.init(config);
		Alerts.init(config);
		ProfitLog.init(config);
		BzOrders.init(config, data);
		FlipFeed.init(config);
		FlipPanel.init(config, data);
		FcfPanel.init(config, data);
		Erp.init(config, data);
		String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("skyprice")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		System.out.println("[SkyPrice] " + version + " loaded");
		boolean[] greeted = {false};
		int[] ticks = {0};
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player != null && ticks[0] % 200 == 0) {          // every 10 s
				try {
					AhScanner.tick(config);                                // scans every scanMinutes
					BzOrders.check();                                      // bazaar orders outbid / undercut (every 30 s)
					if (config.setupDone) FlipFeed.tick(FlipPanel.budget());   // announce the best new AH flips
				} catch (RuntimeException e) {
					System.err.println("[SkyPrice] tick: " + e);
				}
			}
			if (client.player != null && ++ticks[0] % 6000 == 1200) {     // every 5 min (1 min after joining)
				try {
					Double purse = PurseReporter.readPurse();
					if (purse != null) PurseLog.record(purse);
				} catch (RuntimeException ignored) {
					// not in SkyBlock
				}
			}
			if (greeted[0] || client.player == null) return;
			greeted[0] = true;                         // once per game start: proves the mod is loaded
			Chat.say(HudFormatter.line("[SkyPrice " + version + "] ", HudFormatter.GOLD,
					"loaded. Press E: FLIP (right of your inventory) shows flips that fit your purse; FCF is above it.", HudFormatter.GRAY));
			ProfitLog.dailySummary();
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) return;
			OutbidHud.onChat(message);
			Alerts.onChat(message);
		});
		BidHelper.register(config);
		Watch.start(config, data);
		Deals.start(config, data);
		PurseReporter.start(config);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				// Brigadier builders directly: Fabric renamed ClientCommandManager -> ClientCommands in 26.x
				LiteralArgumentBuilder.<FabricClientCommandSource>literal("skyprice")
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("watching").executes(ctx -> {
							var list = Watch.list();
							if (list.isEmpty()) ctx.getSource().sendFeedback(Component.literal("[SkyPrice] Not watching any auctions."));
							for (Watch.Entry e : list) {
								ctx.getSource().sendFeedback(HudFormatter.line("[SkyPrice] ", HudFormatter.GOLD, e.key, HudFormatter.WHITE,
										"  your bid " + HudFormatter.fmt(e.myBid) + "  lowest BIN " + HudFormatter.fmt(e.lastLowest)
												+ "  max profitable " + HudFormatter.fmt(e.maxProfitable), HudFormatter.GRAY));
							}
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unwatch")
								.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("item", StringArgumentType.greedyString())
										.executes(ctx -> {
											String k = StringArgumentType.getString(ctx, "item").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
											ctx.getSource().sendFeedback(Component.literal(Watch.remove(k)
													? "[SkyPrice] Stopped watching " + k : "[SkyPrice] " + k + " wasn't on the watch list"));
											return 1;
										})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("deals").executes(ctx -> {
							var list = Deals.list();
							if (list.isEmpty()) ctx.getSource().sendFeedback(Component.literal(
									"[SkyPrice] No underpriced BINs right now (needs your PC collector running)."));
							for (int i = 0; i < Math.min(10, list.size()); i++) {
								Deals.Deal d = list.get(i);
								ctx.getSource().sendFeedback(HudFormatter.line((i + 1) + ". ", HudFormatter.AQUA,
										(d.count() > 1 ? d.count() + "x " : "") + d.key(), HudFormatter.WHITE,
										String.format(Locale.ROOT, "  BIN %s, %.0f%% under fair, ", HudFormatter.fmt(d.price()), d.discount() * 100),
										HudFormatter.GRAY, "+" + HudFormatter.fmt(d.profit()), 0x55FF55));
							}
							if (!list.isEmpty()) ctx.getSource().sendFeedback(Component.literal("[SkyPrice] /skyprice open <number> opens the auction."));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("open")
								.then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("n", IntegerArgumentType.integer(1))
										.executes(ctx -> {
											int n = IntegerArgumentType.getInteger(ctx, "n");
											var list = Deals.list();
											if (n > list.size()) {
												ctx.getSource().sendError(Component.literal("No deal #" + n + " (see /skyprice deals)"));
												return 0;
											}
											Chat.command("viewauction " + list.get(n - 1).uuid());
											return 1;
										})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("fcf").executes(ctx -> {
							var src = ctx.getSource();
							data.walletReport().whenComplete((d, err) -> Minecraft.getInstance().execute(() -> {
								for (Component c : FcfPanel.chat(d == null ? null : d.json(), d == null ? "no data" : d.source())) src.sendFeedback(c);
							}));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bz")
								.executes(ctx -> {
									ctx.getSource().sendFeedback(HudFormatter.line("[SkyPrice] Bazaar list: ", HudFormatter.GOLD,
											String.join(", ", Erp.bazaarList()), HudFormatter.WHITE));
									ctx.getSource().sendFeedback(Component.literal("[SkyPrice] /skyprice bz add <id>  ·  /skyprice bz remove <id>  ·  /skyprice bz reset"));
									return 1;
								})
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reset").executes(ctx -> {
									config.bazaarWatch = new java.util.ArrayList<>(java.util.Arrays.asList(Erp.DEFAULT_BAZAAR));
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[SkyPrice] Bazaar list reset to stocks + candy."));
									return 1;
								}))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
										.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("id", StringArgumentType.greedyString())
												.executes(ctx -> {
													String id = StringArgumentType.getString(ctx, "id").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
													java.util.List<String> l = Erp.bazaarList();
													if (!l.contains(id)) l.add(id);
													config.bazaarWatch = l;
													config.save();
													data.live(id);
													ctx.getSource().sendFeedback(Component.literal("[SkyPrice] Added " + id + " to the Bazaar list."));
													return 1;
												})))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
										.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("id", StringArgumentType.greedyString())
												.executes(ctx -> {
													String id = StringArgumentType.getString(ctx, "id").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
													java.util.List<String> l = Erp.bazaarList();
													boolean removed = l.remove(id);
													config.bazaarWatch = l;
													config.save();
													ctx.getSource().sendFeedback(Component.literal(removed ? "[SkyPrice] Removed " + id : "[SkyPrice] " + id + " wasn't on the list"));
													return 1;
												}))))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("flips").executes(ctx -> {
							FlipFeed.View v = FlipFeed.view(FlipPanel.budget());
							if (v.flips().isEmpty()) ctx.getSource().sendFeedback(Component.literal("[SkyPrice] No AH flips fit your budget + risk right now"
									+ (v.checking() > 0 ? " (checking " + v.checking() + " more)." : ".")));
							for (int i = 0; i < Math.min(10, v.flips().size()); i++) {
								FlipFeed.Flip f = v.flips().get(i);
								ctx.getSource().sendFeedback(HudFormatter.line((i + 1) + ". ", HudFormatter.AQUA, f.c().name(), HudFormatter.WHITE,
										"  buy " + HudFormatter.fmt(f.c().price()) + " → " + HudFormatter.fmt(f.resale()), HudFormatter.GRAY,
										String.format(Locale.ROOT, "  +%s (%.0f%%)", HudFormatter.fmt(f.profit()), f.pct() * 100), 0x55FF55,
										"  " + Market.eta(f.liq().etaHours()), HudFormatter.DARK));
							}
							if (!v.flips().isEmpty()) ctx.getSource().sendFeedback(Component.literal("[SkyPrice] /skyprice flip <number> opens the auction."));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("flip")
								.then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("n", IntegerArgumentType.integer(1))
										.executes(ctx -> {
											int n = IntegerArgumentType.getInteger(ctx, "n");
											var list = FlipFeed.last();
											if (n > list.size()) {
												ctx.getSource().sendError(Component.literal("No flip #" + n + " (see /skyprice flips)"));
												return 0;
											}
											Chat.command("viewauction " + list.get(n - 1).c().uuid());
											return 1;
										})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bzflips").executes(ctx -> {
							var list = Bazaar.ranking(config, data, FlipPanel.budget());
							if (list.isEmpty()) ctx.getSource().sendFeedback(Component.literal("[SkyPrice] No bazaar flips fit your budget (or the bazaar is still loading)."));
							for (int i = 0; i < Math.min(10, list.size()); i++) {
								Market.BzFlip f = list.get(i);
								ctx.getSource().sendFeedback(HudFormatter.line((i + 1) + ". ", HudFormatter.AQUA, Bazaar.name(f.id(), data), HudFormatter.WHITE,
										"  order " + HudFormatter.fmt(f.order()) + " → offer " + HudFormatter.fmt(f.offer()), HudFormatter.GRAY,
										String.format(Locale.ROOT, "  +%s each (%.0f%%) · ≈+%s/h", HudFormatter.fmt(f.perUnit()), f.pct() * 100,
												HudFormatter.fmt(f.coinsPerHour())), 0x55FF55));
							}
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("profit").executes(ctx -> {
							for (int days : new int[]{0, 6, 29}) {
								ProfitLog.Summary sm = ProfitLog.summary(ProfitLog.startOfDay(days));
								ctx.getSource().sendFeedback(HudFormatter.line("[SkyPrice] " + (days == 0 ? "Today" : (days + 1) + " days") + ": ", HudFormatter.GOLD,
										(sm.profit() >= 0 ? "+" : "") + HudFormatter.fmt(sm.profit()), sm.profit() >= 0 ? 0x55FF55 : 0xFF5555,
										" from " + sm.flips() + " flips" + (sm.best() != null ? ", best " + sm.best().item() + " +"
												+ HudFormatter.fmt(sm.best().profit()) : ""), HudFormatter.GRAY));
							}
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mode")
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("simple").executes(ctx -> {
									config.simpleMode = true;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[SkyPrice] Simple mode: tooltips show only what to do."));
									return 1;
								}))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("pro").executes(ctx -> {
									config.simpleMode = false;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[SkyPrice] Pro mode: tooltips show every number."));
									return 1;
								})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("setup").executes(ctx -> {
							config.setupDone = false;
							config.save();
							ctx.getSource().sendFeedback(Component.literal("[SkyPrice] Press E and click FLIP (right of your inventory) for the 3-tap setup."));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("feed")
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> {
									config.flipFeed = true;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[SkyPrice] AH flip feed on (found during the AH scan)."));
									return 1;
								}))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> {
									config.flipFeed = false;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[SkyPrice] AH flip feed off."));
									return 1;
								})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("erp").executes(ctx -> {
							Erp.toggle();
							ctx.getSource().sendFeedback(Component.literal(Erp.isOn() ? "[SkyPrice] ERP mode on: press E." : "[SkyPrice] ERP mode off."));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unwatchall").executes(ctx -> {
							Watch.clear();
							ctx.getSource().sendFeedback(Component.literal("[SkyPrice] Watch list cleared."));
							return 1;
						}))
						.executes(ctx -> {
							var player = Minecraft.getInstance().player;
							String key = player == null ? null : ItemKeys.keyFor(player.getMainHandItem());
							if (key == null) {
								ctx.getSource().sendError(Component.literal("Hold a SkyBlock item, or use /skyprice <item name or id>"));
								return 0;
							}
							lookup(ctx.getSource(), key);
							return 1;
						})
						.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("item", StringArgumentType.greedyString())
								.executes(ctx -> {
									String q = StringArgumentType.getString(ctx, "item").trim();
									lookup(ctx.getSource(), q.toUpperCase(Locale.ROOT).replace(' ', '_'));
									return 1;
								}))));
	}

	/**
	 * Our doc, or a SkyCofl doc when ours is missing / has too little auction history.
	 * @param fetch false = never start a SkyCofl request (used for the many items the border check scans)
	 */
	static JsonObject docFor(String key, boolean fetch) {
		DataClient.Doc d = data.doc(key);
		JsonObject own = d == null ? null : d.json();
		if (own != null && !CoflClient.thin(own)) return own;
		if (d == null && !fetch) return null;
		if (own == null && d != null && data.live(key) != null) return null;   // a bazaar product we don't have
		JsonObject c = cofl.doc(key, fetch);
		if (c == null) return own;
		JsonObject model = HudFormatter.obj(own, "market", "model");
		if (model != null) HudFormatter.obj(c, "market").add("model", model);   // keep our per-copy valuation model
		return c;
	}

	private static Verdict.Result resultFor(String key, Verdict.Listing listing, Valuation.Attrs attrs, Verdict.Mine mine) {
		DataClient.Doc dd = data.doc(key);
		if (dd == null) return new Verdict.Result(LOADING, null);
		JsonObject j = docFor(key, true);
		if (j == null) return cofl.loading(key) ? new Verdict.Result(LOADING, null) : null;
		try {
			boolean bz = "bz".equals(j.get("kind").getAsString());
			DataClient.Live live = bz ? data.live(key) : null;
			Valuation.Estimate est = bz ? null : Valuation.estimate(j, key, attrs);
			return HudFormatter.render(j, live, config, listing, est, mine);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Your player name (for "Seller: you" on your own listings), or null. */
	static String playerName() {
		var p = Minecraft.getInstance().player;
		return p == null ? null : p.getName().getString();
	}

	private static boolean inBazaarMenu() {
		var s = BidHelper.currentScreen();          // tooltips only render while a screen is open
		if (s == null) return false;
		String t = s.getTitle().getString();
		return t.contains("Bazaar") || t.contains("➜");
	}

	private static void lookup(FabricClientCommandSource src, String key) {
		src.sendFeedback(HudFormatter.line("Looking up " + key + "…", HudFormatter.GRAY));
		data.docAsync(key).whenComplete((d, err) -> Minecraft.getInstance().execute(() -> {
			if (d == null || d.json() == null) {
				src.sendError(Component.literal("No market data for " + key
						+ ". Use the SkyBlock id (e.g. hyperion, enchanted diamond, booster cookie)."));
				return;
			}
			DataClient.Live live = "bz".equals(d.json().get("kind").getAsString()) ? data.live(key) : null;
			for (Component c : HudFormatter.chat(key, d.json(), live, config, d.source())) src.sendFeedback(c);
		}));
	}
}
