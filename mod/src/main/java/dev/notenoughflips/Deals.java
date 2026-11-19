package dev.notenoughflips;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Underpriced BINs found by an optional local server's full AH scan (every 5 min): at least 20 % under fair
 * value, worth 1M+, still profitable after fees. New ones are announced in chat; /nef deals lists
 * them and /nef open <n> opens that auction (you buy it yourself).
 */
final class Deals {
	record Deal(String uuid, String key, double price, int count, double fair, double discount, double profit, long firstSeen) {}

	private static volatile List<Deal> current = List.of();
	private static final Set<String> announced = new HashSet<>();
	private static NefConfig cfg;
	private static DataClient data;
	private static boolean first = true;

	private Deals() {}

	static void start(NefConfig config, DataClient client) {
		cfg = config;
		data = client;
		ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "NEF-deals");
			t.setDaemon(true);
			return t;
		});
		ex.scheduleWithFixedDelay(Deals::poll, 20, Math.max(30, cfg.dealPollSeconds), TimeUnit.SECONDS);
	}

	static List<Deal> list() {
		return current;
	}

	private static void poll() {
		try {
			JsonArray arr = data.localDeals().get(10, TimeUnit.SECONDS);
			if (arr == null) return;                                   // local server not running
			List<Deal> out = new ArrayList<>();
			for (JsonElement e : arr) {
				JsonObject o = e.getAsJsonObject();
				Deal d = new Deal(o.get("uuid").getAsString(), o.get("item_key").getAsString(), o.get("price").getAsDouble(),
						o.get("count").getAsInt(), o.get("fair").getAsDouble(), o.get("discount").getAsDouble(),
						o.get("profit").getAsDouble(), o.get("first_seen").getAsLong());
				if (d.discount() * 100 >= cfg.dealMinDiscountPercent && d.fair() * d.count() >= cfg.dealMinValue) out.add(d);
			}
			current = out;
			if (!cfg.dealAlerts) return;
			int shown = 0;
			for (int i = 0; i < out.size(); i++) {
				Deal d = out.get(i);
				if (!announced.add(d.uuid()) || first) continue;          // first poll: just remember what's already there
				if (shown++ >= 3) continue;                                // don't flood chat
				Chat.say(HudFormatter.line("[NEF] DEAL ", 0x55FF55,
						(d.count() > 1 ? d.count() + "x " : "") + d.key(), HudFormatter.WHITE,
						String.format(Locale.ROOT, "  BIN %s (%.0f%% under fair %s)  ", HudFormatter.fmt(d.price()), d.discount() * 100,
								HudFormatter.fmt(d.fair() * d.count())), HudFormatter.GRAY,
						"+" + HudFormatter.fmt(d.profit()) + " after fees", 0x55FF55,
						"  → /nef open " + (i + 1), HudFormatter.AQUA));
			}
			first = false;
			if (announced.size() > 5000) announced.clear();
		} catch (Exception ignored) {
			// local server offline or slow: try again next round
		}
	}
}
