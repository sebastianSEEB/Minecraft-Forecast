package dev.sebastian.skyprice;

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
	private static final List<Component> LOADING = List.of(HudFormatter.line("Market: loading…", HudFormatter.GRAY));

	@Override
	public void onInitializeClient() {
		config = SkyPriceConfig.load();
		data = new DataClient(config);

		ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipFlag, lines) -> {
			if (!config.enabled) return;
			String key = ItemKeys.keyFor(stack);
			if (key == null) return;                       // not a SkyBlock item
			Verdict.Listing listing = config.showVerdict ? Verdict.listing(lines, stack.getCount()) : null;
			Verdict.Result r = resultFor(key, listing);
			if (r == null || r.lines().isEmpty()) return;
			if (config.showFlipBanner && r.banner() != null && !lines.isEmpty()) lines.add(1, r.banner());   // under the item name
			if (r.nextBid() != null && r.bidCap() != null && r.maxBid() != null)
				BidHelper.hovered(key, r.suggestedBid(), r.nextBid(), r.bidCap(), r.maxBid(), r.lowestBin());
			lines.add(Component.empty());
			lines.addAll(r.lines());
		});

		Highlight.init(config, data);
		BidHelper.register(config);
		Watch.start(config, data);
		Deals.start(config, data);

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

	private static Verdict.Result resultFor(String key, Verdict.Listing listing) {
		DataClient.Doc d = data.doc(key);
		if (d == null) return new Verdict.Result(LOADING, null);
		if (d.json() == null) return null;            // item never traded / not published
		try {
			DataClient.Live live = "bz".equals(d.json().get("kind").getAsString()) ? data.live(key) : null;
			return HudFormatter.render(d.json(), live, config, listing);
		} catch (RuntimeException e) {
			return null;
		}
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
