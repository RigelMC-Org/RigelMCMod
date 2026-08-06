package org.rigelmc.world;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Blocks a normal teleport landing a player in the admin world without access, catching
 * it <i>before</i> it completes - covers {@code /adminworld} itself, Essentials'
 * {@code /tp(a)}, and any other plugin's ordinary teleport alike. Cheaper than a
 * per-movement-tick check for the common case, and better UX (the player never actually
 * sees themselves arrive at all).
 *
 * <p><b>Not actually sufficient alone</b>, despite this class's own earlier javadoc
 * claiming otherwise - user-reported, a real incident: Bukkit does not reliably fire
 * {@link PlayerTeleportEvent} for a player riding a vehicle that itself gets moved
 * cross-world, which is exactly how a reported exploit got a player into the admin world
 * anyway (right-click-spamming a boat into existence to ride through). See {@link
 * AdminWorldPresenceGuard} for the reactive safety net + periodic sweep that actually
 * closes that gap - this class remains the fast, proactive first line of defense for
 * every teleport that isn't vehicle-involved, not the sole mechanism.</p>
 *
 * <p>TFM achieves roughly the same end result with a {@code PlayerMoveEvent} validator
 * (which also happens to fire on teleports) - a reactive, per-tick check with the same
 * class of "brief window" gap {@link AdminWorldPresenceGuard} exists to close, not an
 * inherently more complete design than the proactive-plus-reactive pair used here.</p>
 */
public final class AdminWorldTeleportGuard implements Listener {

    private final AdminWorldService adminWorldService;

    public AdminWorldTeleportGuard(@NotNull AdminWorldService adminWorldService) {
        this.adminWorldService = adminWorldService;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(@NotNull PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        Optional<World> adminWorld = adminWorldService.world();
        if (adminWorld.isEmpty() || !to.getWorld().equals(adminWorld.get())) {
            return;
        }
        Player player = event.getPlayer();
        if (adminWorldService.canAccess(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(
                Component.text("You don't have permission to enter the admin world.", NamedTextColor.RED));
    }
}
