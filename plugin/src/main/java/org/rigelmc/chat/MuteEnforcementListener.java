package org.rigelmc.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.punish.mute.MuteService;

/**
 * Cancels chat for muted players. {@link AsyncChatEvent} already runs off the main
 * thread (per its name/contract), so a direct synchronous JDBC lookup here doesn't
 * violate CONTRIBUTING.md's main-thread rule - there's no thread hop needed, unlike
 * command execution which starts on the main thread.
 */
public final class MuteEnforcementListener implements Listener {

    private final MuteService muteService;
    private final Logger logger;

    public MuteEnforcementListener(@NotNull MuteService muteService, @NotNull Logger logger) {
        this.muteService = muteService;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(@NotNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        try {
            if (muteService.isMuted(player.getUniqueId(), System.currentTimeMillis())) {
                event.setCancelled(true);
                player.sendMessage(Component.text("You are muted and cannot chat.", NamedTextColor.RED));
            }
        } catch (SQLException e) {
            // Fail open on a DB error here - a chat outage is worse than a temporarily
            // unenforced mute, and this is logged loudly so it doesn't go unnoticed.
            logger.log(Level.WARNING, "Failed to check mute status for " + player.getName(), e);
        }
    }
}
