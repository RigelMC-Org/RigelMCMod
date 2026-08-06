package org.rigelmc.scoreboard;

import org.bukkit.Bukkit;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;

/**
 * Wraps {@link ScoreboardService} as a toggleable module - see {@code config.yml}'s
 * {@code scoreboard} section and {@code modules.scoreboard.enabled}.
 *
 * <p>Registers no join listener of its own - every player is already assigned the
 * shared {@link org.bukkit.scoreboard.Scoreboard} this module's objective lives on by
 * {@code core.PlayerLoginListener} (via {@code rank.NameTagService#applyScoreboard},
 * unconditionally, since nametag coloring needs that to happen regardless of whether
 * this module is enabled) - so there's nothing left for this module to do per-join.</p>
 */
public final class ScoreboardModule implements PluginModule {

    private final ScoreboardService scoreboardService;

    public ScoreboardModule(ScoreboardService scoreboardService) {
        this.scoreboardService = scoreboardService;
    }

    @Override
    public String id() {
        return "scoreboard";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        scoreboardService.refresh(plugin.rigelConfig());

        // Refresh alongside the tab list footer (ChatModule) so {online}/{max} stays
        // current - same 5s cadence, cheap either way.
        Bukkit.getScheduler()
                .runTaskTimer(plugin, () -> scoreboardService.refresh(plugin.rigelConfig()), 20L, 100L);
    }
}
