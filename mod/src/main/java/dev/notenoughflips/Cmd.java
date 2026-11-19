package dev.notenoughflips;

/**
 * The only commands the mod ever sends, each after a click of yours (or a snipe alert with auto-open on).
 * Arguments are checked so nothing read from the network (auction lists, the data site) can turn into another command.
 */
final class Cmd {
	private Cmd() {}

	static void viewAuction(String uuid) {
		if (uuid == null) return;
		String u = uuid.replace("-", "");
		if (u.matches("[0-9a-fA-F]{32}")) Chat.command("viewauction " + u);
	}

	static void bazaar(String search) {
		if (search == null) return;
		String s = search.replaceAll("[^A-Za-z0-9 '()\\-]", "").trim();
		if (!s.isEmpty() && s.length() <= 40) Chat.command("bz " + s);
	}
}
