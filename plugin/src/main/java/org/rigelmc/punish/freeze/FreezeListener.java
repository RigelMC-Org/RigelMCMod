package org.rigelmc.punish.freeze;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
 *
 * <p><b>Hard freeze</b> ({@link FreezeService#isHardFrozen}) additionally blocks commands,
 * chat, and interaction outright - see {@link #onCommand}/{@link #onChat}/{@link
 * #onInteract}/{@link #onInteractEntity}. This is what replaces TFM's real {@code /lockup}
 * in this project (see {@link FreezeService}'s own javadoc for why) - ordinary cancellable
 * events, not packet-level input suppression.</p>
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        if (!isHardFrozenNonStaff(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(@NotNull AsyncChatEvent event) {
        if (!isHardFrozenNonStaff(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (!isHardFrozenNonStaff(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (!isHardFrozenNonStaff(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        freezeService.clearOnQuit(event.getPlayer().getUniqueId());
    }

    private boolean isHardFrozenNonStaff(Player player) {
        if (permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator")) {
            return false;
        }
        return freezeService.isHardFrozen(player.getUniqueId());
    }

    private void warn(Player player) {
        player.sendMessage(Component.text("You are frozen and cannot do that right now.", NamedTextColor.RED));
    }
}
