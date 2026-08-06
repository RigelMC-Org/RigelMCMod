package org.rigelmc.rank;

import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

/**
 * Colors each ranked/titled player's <b>overhead nametag</b> (the floating name shown
 * above their head in the 3D world) to match their rank/title color - a scoreboard
 * {@link Team}'s color is the only thing that actually affects this; Adventure
 * display-name/{@code Component}-based APIs (used for chat/tab list - see
 * {@code chat.PlayerDisplayService}) have no effect on it at all.
 *
 * <p>Default-rank (auto-op'd, no real RigelMCMod rank) players get no team and no color -
 * "OPs by default have no tag color" - only real ranked/titled players do, matching
 * {@link Rank#nameColor(String)}/{@link Title#nameColor(String)} exactly (same color as
 * their chat/tab-list name).</p>
 *
 * <p>Operates on a {@link Scoreboard} instance <b>injected</b> at construction rather
 * than the server's main scoreboard or one it creates itself - it must be the exact same
 * shared instance every player's client actually has active
 * ({@code Player#setScoreboard}), which is also the one {@code scoreboard.ScoreboardService}
 * registers the optional sidebar objective on. Team-color visibility and sidebar content
 * are otherwise independent Bukkit features that happen to require the same Scoreboard
 * object be "the active one" for a player's client to see either of them - see
 * {@code RigelMCMod#initializeDataLayer} for where the one shared instance is created and
 * handed to both.</p>
 */
public final class NameTagService {

    private static final String TEAM_PREFIX = "rmcm_";

    private final Scoreboard scoreboard;

    public NameTagService(@NotNull Scoreboard scoreboard) {
        this.scoreboard = scoreboard;
    }

    /**
     * Puts every online player's client onto the shared scoreboard - must run on every
     * join, unconditionally, regardless of whether the sidebar module
     * ({@code modules.scoreboard.enabled}) is on: nametag coloring is its own feature,
     * not gated by that toggle.
     */
    public void applyScoreboard(@NotNull Player player) {
        player.setScoreboard(scoreboard);
    }

    /**
     * Recomputes and applies this player's nametag-color team from their current rank +
     * held titles, removing them from any previously-assigned RigelMCMod team first.
     * Main-thread only (Scoreboard/Team API) - call after {@link #applyScoreboard} on
     * join, and again after any rank/title change.
     */
    public void applyTeam(@NotNull Player player, @NotNull Rank rank, @NotNull Set<String> titleIds) {
        removeFromManagedTeam(player);
        resolveColorTeamId(rank, titleIds).ifPresent(teamId -> teamFor(teamId).addEntry(player.getName()));
    }

    /** Removes this player from any RigelMCMod nametag-color team. Call on quit. */
    public void clear(@NotNull Player player) {
        removeFromManagedTeam(player);
    }

    @NotNull
    private static Optional<String> resolveColorTeamId(Rank rank, Set<String> titleIds) {
        if (!rank.isDefault() && Rank.nameColor(rank.id()).isPresent()) {
            return Optional.of(rank.id());
        }
        return titleIds.stream().filter(id -> Title.nameColor(id).isPresent()).findFirst();
    }

    @NotNull
    private Team teamFor(String rankOrTitleId) {
        NamedTextColor color = Rank.nameColor(rankOrTitleId)
                .or(() -> Title.nameColor(rankOrTitleId))
                .orElseThrow(() -> new IllegalStateException("No configured name color for " + rankOrTitleId));
        String teamName = TEAM_PREFIX + rankOrTitleId;
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        team.color(color);
        return team;
    }

    private void removeFromManagedTeam(Player player) {
        Team current = scoreboard.getEntryTeam(player.getName());
        if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
            current.removeEntry(player.getName());
        }
    }
}
