package org.rigelmc.guild.plot;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.protect.area.AreaRegion;
import org.rigelmc.protect.area.ProtectAreaService;
import org.rigelmc.rank.PermissionGate;

/**
 * User-requested, on top of the existing whole-plot-world lockdown ({@code
 * GuildPlotWorldService}'s class javadoc): nobody should be able to break or place anything
 * outside their own plot in the guild plot world - not even the border wall itself, and not
 * even staff, unless a Senior Admin has explicitly turned on {@link GuildPlotBypassService}
 * via {@code /guild admin plotbypass} first.
 *
 * <p><b>Deliberately narrower than the general protect-area floor</b>: {@code
 * protect.area.ProtectAreaService#isBypassing} already lets any Moderator+ silently bypass
 * every protected region project-wide (an intentional, established floor - see that
 * method's own javadoc), which this class does <i>not</i> touch or weaken for any other use
 * of {@code /protectarea} - only the guild plot world gets the stricter rule. This runs as
 * its own listener, entirely additive, at {@link EventPriority#HIGH} (after {@code
 * protect.area.AreaProtectionListener}'s own default-priority handling) so it can observe
 * and, if needed, re-cancel an event the general Moderator+ floor already let through -
 * never the reverse; an event the generic layer already blocked is left alone.</p>
 *
 * <p><b>Scope</b>: only {@link BlockBreakEvent}/{@link BlockPlaceEvent} - the literal
 * "break/place" ask. Explosions, buckets, pistons, hanging entities, and WorldEdit/FAWE
 * edits inside the plot world still fall back to the general Moderator+ floor, unchanged -
 * not extended here, same as every other known, documented scope boundary in this project.</p>
 */
public final class GuildPlotBoundaryGuard implements Listener {

    private final RigelMCMod plugin;
    private final ProtectAreaService protectAreaService;
    private final PermissionGate permissionGate;
    private final GuildPlotBypassService bypassService;

    public GuildPlotBoundaryGuard(
            @NotNull RigelMCMod plugin, @NotNull ProtectAreaService protectAreaService,
            @NotNull PermissionGate permissionGate, @NotNull GuildPlotBypassService bypassService) {
        this.plugin = plugin;
        this.protectAreaService = protectAreaService;
        this.permissionGate = permissionGate;
        this.bypassService = bypassService;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (shouldBlock(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        if (shouldBlock(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Bypass is session-only (see {@link GuildPlotBypassService}'s javadoc) - always reset on quit. */
    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        bypassService.clear(event.getPlayer().getUniqueId());
    }

    /** @return {@code true} if this event should be denied - already-cancelled events are left alone (nothing more to decide). */
    private boolean shouldBlock(@NotNull Player player, @NotNull Block block) {
        String plotWorldName = plugin.rigelConfig().guildPlotWorldName();
        if (!block.getWorld().getName().equals(plotWorldName)) {
            return false; // not the guild plot world at all
        }
        Location loc = block.getLocation();
        Optional<AreaRegion> region =
                protectAreaService.effectiveRegionAt(plotWorldName, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (region.isEmpty() || region.get().isMemberOrOwner(player.getUniqueId())) {
            return false; // no protection here at all, or they're building inside their own plot - always fine
        }
        // Outside their own plot (the boundary itself, or someone else's plot) - only an
        // explicitly-bypassing Senior Admin may proceed.
        boolean staffBypassActive =
                permissionGate.hasAtLeastCached(player.getUniqueId(), "senior_admin") && bypassService.isBypassing(player.getUniqueId());
        return !staffBypassActive;
    }
}
