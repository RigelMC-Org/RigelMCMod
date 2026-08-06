package org.rigelmc.core;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.rank.Rank;
import org.rigelmc.rank.RankService;

/**
 * Enforces {@link LockdownModule}'s toggle at login - matches TFM's own {@code
 * LoginProcess}, which force-allows admins before it ever reaches the lockdown check.
 * Runs on {@link AsyncPlayerPreLoginEvent} (already off-thread, same reasoning as {@code
 * punish.ban.BanEnforcementListener}), at {@code NORMAL} priority so a ban (checked at
 * {@code HIGH}) still wins if both would deny the login.
 */
final class LockdownListener implements Listener {

    private final LockdownModule module;
    private final RankService rankService;
    private final Logger logger;

    LockdownListener(@NotNull LockdownModule module, @NotNull RankService rankService, @NotNull Logger logger) {
        this.module = module;
        this.rankService = rankService;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(@NotNull AsyncPlayerPreLoginEvent event) {
        if (!module.isActive() || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            if (rankService.hasAtLeast(event.getUniqueId(), Rank.MODERATOR.id())) {
                return;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING,
                    "Failed to check rank for lockdown check on " + event.getName() + " - denying login", e);
        }
        event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Server is currently in lockdown mode.", NamedTextColor.RED));
    }
}
