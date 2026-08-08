package org.rigelmc.protect.antigrief;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * Anti-nuke: rate-limits block break/place per player over a 1-second sliding window, and
 * separately flags "freecam nuking" - breaking/placing a block farther from the player's
 * eye location than their actual interaction range should allow, a classic nuke-client tell
 * since a legitimate client can't send that packet through normal play. TFM ref:
 * AntiNuke.java. Runs synchronously at LOW priority (cancellation must happen sync, and
 * running early lets a cancellation here pre-empt any other plugin's own handling) - cheap
 * per event, one map lookup plus a small deque prune, no DB round-trip on the hot path.
 *
 * <p>Reads {@code plugin.rigelConfig()} fresh on every event rather than caching a {@code
 * RigelConfig} reference at construction time - the field is replaced wholesale on
 * {@code /rmcm reload}, so holding onto the old instance would silently stop picking up
 * config changes.</p>
 */
public final class AntiNukeGuard implements Listener {

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;
    private final PerPlayerRateLimiter<UUID> breaks = new PerPlayerRateLimiter<>(1_000);
    private final PerPlayerRateLimiter<UUID> places = new PerPlayerRateLimiter<>(1_000);

    public AntiNukeGuard(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        if (!plugin.rigelConfig().antiNukeEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (isFreecam(player, event.getBlock().getLocation())) {
            breach(player, "freecam block break");
            event.setCancelled(true);
            return;
        }
        int count = breaks.recordAndCount(player.getUniqueId(), System.currentTimeMillis());
        if (count > plugin.rigelConfig().antiNukeMaxBreaksPerSecond()) {
            breach(player, "block-break rate limit");
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        if (!plugin.rigelConfig().antiNukeEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (isFreecam(player, event.getBlock().getLocation())) {
            breach(player, "freecam block place");
            event.setCancelled(true);
            return;
        }
        int count = places.recordAndCount(player.getUniqueId(), System.currentTimeMillis());
        if (count > plugin.rigelConfig().antiNukeMaxPlacesPerSecond()) {
            breach(player, "block-place rate limit");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        breaks.clear(event.getPlayer().getUniqueId());
        places.clear(event.getPlayer().getUniqueId());
    }

    private boolean isFreecam(Player player, Location blockLocation) {
        double range = plugin.rigelConfig().antiNukeFreecamRange();
        return range > 0 && player.getEyeLocation().distance(blockLocation) > range;
    }

    private void breach(Player player, String reason) {
        AntiGriefSupport.applyAction(
                plugin.rigelConfig().antiNukeAction(),
                player,
                permissionGate,
                "[AntiNuke] " + player.getName() + " triggered " + reason + ".",
                "Disconnected: suspicious block activity detected.");
    }
}
