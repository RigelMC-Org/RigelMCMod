package org.rigelmc.protect.antigrief;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * TNT/explosion block-damage and fire-spread toggles - TFM ref: {@code EventBlocker}'s
 * {@code ALLOW_EXPLOSIONS}/{@code ALLOW_FIRE_SPREAD} handling, studied directly, with one
 * deliberate deviation: TFM's own "allowed" path still always zeroes the explosion yield,
 * so terrain is never actually damaged by TNT either way in TFM's real behavior. Here the
 * toggle means what it says instead: disabled clears the affected block list (the
 * explosion still happens - sound, entity damage, knockback - just no block damage),
 * enabled leaves block damage fully vanilla. See config.yml for the full rationale.
 *
 * <p>Reads {@code plugin.rigelConfig()} fresh on every event rather than caching a
 * reference - see {@link AntiNukeGuard}'s javadoc for why.</p>
 */
public final class ExplosionAndFireGuard implements Listener {

    private final RigelMCMod plugin;

    public ExplosionAndFireGuard(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(@NotNull EntityExplodeEvent event) {
        if (!plugin.rigelConfig().tntDamageEnabled()) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(@NotNull BlockExplodeEvent event) {
        if (!plugin.rigelConfig().tntDamageEnabled()) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(@NotNull BlockBurnEvent event) {
        if (!plugin.rigelConfig().fireSpreadEnabled()) {
            event.setCancelled(true);
        }
    }

    /**
     * Flint-and-steel placement is deliberately left untouched regardless of this
     * setting, only spread-caused ignition is gated - matches TFM's own separate {@code
     * allow.fire_place} vs {@code allow.fire_spread} distinction; only the latter was
     * requested here.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(@NotNull BlockIgniteEvent event) {
        if (event.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                && !plugin.rigelConfig().fireSpreadEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(@NotNull BlockSpreadEvent event) {
        if (event.getSource().getType() == Material.FIRE && !plugin.rigelConfig().fireSpreadEnabled()) {
            event.setCancelled(true);
        }
    }
}
