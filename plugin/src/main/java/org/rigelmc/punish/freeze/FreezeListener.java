package org.rigelmc.punish.freeze;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.rank.PermissionGate;

/**
 * Pins a frozen player in place - TFM ref: {@code freeze.Freezer#onPlayerMove}. Staff
 * (Moderator+) are always exempt, matching TFM's own admin exemption, so a global freeze
 * never accidentally traps the staff responding to whatever prompted it. Runs at {@code
 * HIGH} priority so it has the final say over any lower-priority movement guard (e.g.
 * {@code protect.antigrief.MovementBoundsGuard}'s world-border correction) that might also
 * touch {@code event.setTo(...)} on the same move.
 */
public final class FreezeListener implements Listener {

    private final FreezeService freezeService;
    private final PermissionGate permissionGate;

    public FreezeListener(@NotNull FreezeService freezeService, @NotNull PermissionGate permissionGate) {
        this.freezeService = freezeService;
        this.permissionGate = permissionGate;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(@NotNull PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        if (permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator")) {
            return;
        }
        if (!freezeService.isFrozen(player.getUniqueId())) {
            return;
        }

        player.setFlying(true);
        Location location = freezeService.frozenLocationOf(player.getUniqueId());
        event.setTo(location != null ? location : event.getFrom());
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        freezeService.clearOnQuit(event.getPlayer().getUniqueId());
    }
}
