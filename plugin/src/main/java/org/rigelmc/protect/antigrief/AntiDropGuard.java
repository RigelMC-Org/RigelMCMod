package org.rigelmc.protect.antigrief;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * Throttles {@link PlayerDropItemEvent} by rate - defends against item-drop lag bombs
 * (rapidly dropping/re-picking-up stacks to flood the world with dropped-item entities).
 * TFM ref: AntiDrop.java. Cancel-only, no kick action - a drop-spam attempt is disruptive
 * but not itself dangerous enough to warrant ejection. Reads {@code plugin.rigelConfig()}
 * fresh on every event - see {@link AntiNukeGuard}'s javadoc for why.
 */
public final class AntiDropGuard implements Listener {

    private final RigelMCMod plugin;
    private final PerPlayerRateLimiter drops = new PerPlayerRateLimiter(10_000);

    public AntiDropGuard(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        if (!plugin.rigelConfig().antiDropEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        int count = drops.recordAndCount(player.getUniqueId(), System.currentTimeMillis());
        if (count > plugin.rigelConfig().antiDropMaxDropsPer10s()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        drops.clear(event.getPlayer().getUniqueId());
    }
}
