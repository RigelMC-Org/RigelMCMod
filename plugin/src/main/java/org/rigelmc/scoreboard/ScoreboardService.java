package org.rigelmc.scoreboard;

import java.util.HashSet;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
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
 * <p>Uses {@link Score#customName(Component)} (confirmed present in Paper 26.1.2 via
 * direct bytecode inspection, not assumed) to decouple each line's actual displayed
 * rich text from its internal entry key - the modern replacement for the old
 * unique-color-code-prefix hack scoreboard plugins used to need.</p>
 *
 * <p>Takes its {@link Scoreboard} <b>injected</b> rather than creating its own - it must
 * be the same shared instance {@code rank.NameTagService} registers nametag-color teams
 * on and every player's client is actually assigned via {@code Player#setScoreboard}
 * (centralized in {@code core.PlayerLoginListener}, unconditionally - not gated behind
 * this module's own enabled toggle, since nametag coloring is a separate feature that
 * must keep working even with the sidebar disabled). See
 * {@code RigelMCMod#initializeDataLayer} for where it's created.</p>
 */
public final class ScoreboardService {

    private static final String OBJECTIVE_NAME = "rigelmcmod";

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

            Score score = objective.getScore("rigel_line_" + i);
            score.customName(lineComponent);
            score.setScore(size - i); // higher score renders higher up - first config line on top
        }
    }
}
