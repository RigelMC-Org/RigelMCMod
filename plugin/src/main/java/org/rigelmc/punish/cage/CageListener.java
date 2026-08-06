package org.rigelmc.punish.cage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/** TFM ref: {@code caging.Cager}. Enforces a cage's block-break/place lock and leash radius. */
public final class CageListener implements org.bukkit.event.Listener {

    private final CageService cageService;

    public CageListener(@NotNull CageService cageService) {
        this.cageService = cageService;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (cageService.isCaged(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        if (cageService.isCaged(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMove(@NotNull PlayerMoveEvent event) {
        if (!event.hasChangedPosition() || event.getTo() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!cageService.isOutOfCage(player, event.getTo())) {
            return;
        }
        cageService.teleportToCenterAndRegenerate(player);
        player.sendMessage(Component.text("You may not leave your cage.", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        cageService.onQuitOrKick(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(@NotNull PlayerKickEvent event) {
        cageService.onQuitOrKick(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        cageService.onJoin(event.getPlayer());
    }
}
