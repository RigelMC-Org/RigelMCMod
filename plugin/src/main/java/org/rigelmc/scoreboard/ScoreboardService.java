package org.rigelmc.scoreboard;

import java.util.HashSet;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.core.RigelConfig;

/**
 * Configurable, toggleable sidebar scoreboard - title + a list of MiniMessage-formatted
 * lines ({@code {online}}/{@code {max}}/{@code {ip}} placeholders - see {@code
 * RigelConfig#serverIpDomain}), refreshed periodically like the tab list footer. One
 * shared {@link Scoreboard}/{@link Objective} rather than a
 * per-player one, since the content is server-wide, not per-viewer - simpler and cheaper
 * to keep in sync.
 *
 * <p>User-requested: each line's real text is carried on a per-line {@link Team}'s
 * prefix/suffix, not {@link Score#customName(Component)} - this project's playerbase
 * includes 1.8.8/1.12.2 Eaglercraft clients (see {@code rank.RankService}'s javadoc), which
 * speak a genuinely older wire protocol, translated by a proxy/bridge rather than natively
 * understood. {@code Score#customName} relies on a scoreboard packet field that only exists
 * starting with the 1.20.3 protocol rewrite; a client (or translation layer) that doesn't
 * understand that field falls back to showing the scoreboard's raw internal entry key
 * instead of the real text - exactly what was reported (a sidebar reading literally
 * "rigel_line_0", "rigel_line_1", ...). A Team's prefix/suffix, by contrast, is the
 * original mechanism every legacy Minecraft client (and every proxy/bridge that has ever
 * needed to translate a scoreboard down to one) has supported since Minecraft 1.5 - the
 * classic "invisible entry + prefix/suffix" trick every cross-version scoreboard plugin
 * uses, restored here via {@link #invisibleEntry} and {@link #splitForLegacyTeam}.</p>
 *
 * <p>Takes its {@link Scoreboard} <b>injected</b> rather than creating its own - it must
 * be the same shared instance {@code rank.NameTagService} registers nametag-color teams
 * on and every player's client is actually assigned via {@code Player#setScoreboard}
 * (centralized in {@code core.PlayerLoginListener}, unconditionally - not gated behind
 * this module's own enabled toggle, since nametag coloring is a separate feature that
 * must keep working even with the sidebar disabled). See
 * {@code RigelMCMod#initializeDataLayer} for where it's created. This class's own teams use
 * a distinct {@value #TEAM_PREFIX} name prefix so they can never collide with {@code
 * NameTagService}'s {@code rmcm_}-prefixed rank/title color teams on the same shared
 * scoreboard.</p>
 */
public final class ScoreboardService {

    private static final String OBJECTIVE_NAME = "rigelmcmod";
    private static final String TEAM_PREFIX = "rigelsb_";

    /** Pre-1.13 wire-protocol limit for a Team prefix/suffix string, in characters. */
    private static final int LEGACY_PART_MAX = 16;

    /** "Digits" used to build a unique, invisible (pure color-code) entry per line. */
    private static final char[] INVISIBLE_DIGITS = "0123456789abcdef".toCharArray();

    private final Scoreboard scoreboard;
    private final Objective objective;

    public ScoreboardService(@NotNull Scoreboard scoreboard) {
        this.scoreboard = scoreboard;
        this.objective =
                scoreboard.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, Component.text("RigelMC"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    /** Rebuilds the title and every line from config. Main-thread only (Scoreboard API). */
    public void refresh(@NotNull RigelConfig config) {
        objective.displayName(MiniMessage.miniMessage().deserialize(config.scoreboardTitle()));

        for (String entry : new HashSet<>(scoreboard.getEntries())) {
            scoreboard.resetScores(entry);
        }
        for (Team team : new HashSet<>(scoreboard.getTeams())) {
            if (team.getName().startsWith(TEAM_PREFIX)) {
                team.unregister();
            }
        }

        List<String> lines = config.scoreboardLines();
        int size = lines.size();
        int onlineCount = Bukkit.getOnlinePlayers().size();
        int maxCount = Bukkit.getMaxPlayers();
        String ipDomain = config.serverIpDomain();

        for (int i = 0; i < size; i++) {
            String raw = lines.get(i)
                    .replace("{online}", String.valueOf(onlineCount))
                    .replace("{max}", String.valueOf(maxCount))
                    .replace("{ip}", ipDomain);
            Component lineComponent = MiniMessage.miniMessage().deserialize(raw);
            String legacy = LegacyComponentSerializer.legacySection().serialize(lineComponent);
            String[] prefixAndSuffix = splitForLegacyTeam(legacy);

            String entry = invisibleEntry(i);
            Team team = scoreboard.registerNewTeam(TEAM_PREFIX + i);
            team.addEntry(entry);
            team.setPrefix(prefixAndSuffix[0]);
            team.setSuffix(prefixAndSuffix[1]);

            Score score = objective.getScore(entry);
            score.setScore(size - i); // higher score renders higher up - first config line on top
        }
    }

    /**
     * Splits a legacy-formatted line into a Team prefix/suffix pair, each capped at
     * {@value #LEGACY_PART_MAX} characters - the pre-1.13 wire-protocol limit a
     * 1.8.8/1.12.2 client (or a bridge translating down to one) actually enforces,
     * regardless of what the modern Paper API itself would otherwise allow.
     *
     * <p>Cuts only on a color-code boundary (never mid-{@code §X} pair) and carries the
     * active color/formatting state across the cut via {@link
     * ChatColor#getLastColors(String)} - Bukkit's own utility for exactly this purpose -
     * so a line that changes color partway through doesn't fall back to default white
     * after the 16-character split.</p>
     *
     * @return a 2-element array: {@code [prefix, suffix]}
     */
    @NotNull
    private static String[] splitForLegacyTeam(@NotNull String legacy) {
        if (legacy.length() <= LEGACY_PART_MAX) {
            return new String[] {legacy, ""};
        }
        String prefix = legacy.substring(0, LEGACY_PART_MAX);
        if (prefix.charAt(prefix.length() - 1) == ChatColor.COLOR_CHAR) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        String remainder = legacy.substring(prefix.length());
        String suffix = ChatColor.getLastColors(prefix) + remainder;
        if (suffix.length() > LEGACY_PART_MAX) {
            suffix = suffix.substring(0, LEGACY_PART_MAX);
            if (suffix.charAt(suffix.length() - 1) == ChatColor.COLOR_CHAR) {
                suffix = suffix.substring(0, suffix.length() - 1);
            }
        }
        return new String[] {prefix, suffix};
    }

    /**
     * A unique, invisible (pure color-code) scoreboard entry for line {@code index} - with
     * the real text now carried by the line's Team prefix/suffix, the entry itself must
     * render as nothing so only the prefix/suffix show. Two-hex-digit encoding supports 256
     * unique lines, far past vanilla's own 15-line sidebar cap, while staying well under the
     * 16-character legacy entry-name limit.
     */
    @NotNull
    private static String invisibleEntry(int index) {
        char high = INVISIBLE_DIGITS[(index / INVISIBLE_DIGITS.length) % INVISIBLE_DIGITS.length];
        char low = INVISIBLE_DIGITS[index % INVISIBLE_DIGITS.length];
        return "" + ChatColor.COLOR_CHAR + high + ChatColor.COLOR_CHAR + low;
    }
}
