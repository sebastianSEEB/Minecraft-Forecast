package dev.notenoughflips;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

/**
 * Reads "Purse: 12,345,678" from the SkyBlock sidebar every few minutes while you play and sends it to your
 * local server (POST /wallet), so your coins are tracked even without a Hypixel API key.
 * Read-only: nothing is sent to Hypixel.
 */
final class PurseReporter {
	private static final Pattern PURSE = Pattern.compile("(?:Purse|Piggy):\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	private PurseReporter() {}

	static void start(NefConfig cfg) {
		if (!cfg.reportPurse || cfg.localServer == null || cfg.localServer.isBlank()) return;
		ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "NEF-purse");
			t.setDaemon(true);
			return t;
		});
		int every = Math.max(60, cfg.purseReportSeconds);
		ex.scheduleWithFixedDelay(() -> Minecraft.getInstance().execute(() -> {
			try {
				Double purse = readPurse();
				if (purse != null) send(cfg, purse);
			} catch (RuntimeException ignored) {
				// not in SkyBlock / scoreboard not ready
			}
		}), 30, every, TimeUnit.SECONDS);
	}

	/** Must run on the client thread. */
	static Double readPurse() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		Scoreboard sb = mc.level.getScoreboard();
		Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (obj == null) return null;
		for (ScoreHolder holder : sb.getTrackedPlayers()) {
			if (!sb.listPlayerScores(holder).containsKey(obj)) continue;
			PlayerTeam team = sb.getPlayersTeam(holder.getScoreboardName());
			if (team == null) continue;
			String line = (team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString()).replaceAll("§.", "");
			Matcher m = PURSE.matcher(line);
			if (m.find()) return Double.parseDouble(m.group(1).replace(",", ""));
		}
		return null;
	}

	private static void send(NefConfig cfg, double purse) {
		Minecraft mc = Minecraft.getInstance();
		String player = mc.player == null ? "" : mc.player.getName().getString();
		String body = "{\"purse\":" + (long) purse + ",\"player\":\"" + player.replaceAll("[^A-Za-z0-9_]", "") + "\"}";
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.localServer + "/wallet")).timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
	}
}
