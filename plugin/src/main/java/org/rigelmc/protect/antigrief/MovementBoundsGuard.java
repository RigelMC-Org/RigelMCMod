package org.rigelmc.protect.antigrief;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * Movement validator + world border. Applies a real {@link World#getWorldBorder()} (radius
 * from 0,0 - see config.yml's {@code protect.anti-grief.movement.world-border.radius}) to
 * every loaded/loading world, and separately relocates a player whose move/teleport/join
 * lands outside it - a vanilla {@code WorldBorder} alone only resists normal walking, not a
 * teleport straight past it. "alert-only" mode (config) logs/notifies staff without
 * actually relocating, for trialling a new radius before enforcing it. TFM ref:
 * MovementValidator.java. Reads {@code plugin.rigelConfig()} fresh on every event - see
 * {@link AntiNukeGuard}'s javadoc for why.
 *
 * <p>Move/teleport corrections use {@code event.setTo(...)} rather than a separate
 * {@code player.teleport()} call - calling {@code teleport()} from inside a
 * {@link PlayerTeleportEvent} handler would recursively fire another teleport event; {@code
 * setTo} redirects the in-flight event instead. {@link PlayerJoinEvent} has no such event
 * to redirect, so it teleports directly - safe there since nothing re-fires a join.</p>
 */
public final class MovementBoundsGuard implements Listener {

    private static final Component OUT_OF_BOUNDS_MESSAGE = Component.text(
            "You were outside the world border and have been returned to spawn.", NamedTextColor.YELLOW);

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;

    public MovementBoundsGuard(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    /** Applies the configured border to every already-loaded world - call once on enable. */
    public void applyToLoadedWorlds(@NotNull Iterable<World> worlds) {
        for (World world : worlds) {
            applyBorder(world);
        }
    }

    @EventHandler
    public void onWorldLoad(@NotNull WorldLoadEvent event) {
        applyBorder(event.getWorld());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(@NotNull PlayerMoveEvent event) {
        if (!plugin.rigelConfig().movementGuardEnabled() || event.getTo() == null || !isOutOfBounds(event.getTo())) {
            return;
        }
        if (plugin.rigelConfig().movementGuardAlertOnly()) {
            alert(event.getPlayer());
            return;
        }
        event.setTo(spawnOf(event.getPlayer().getWorld()));
        event.getPlayer().sendMessage(OUT_OF_BOUNDS_MESSAGE);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(@NotNull PlayerTeleportEvent event) {
        if (!plugin.rigelConfig().movementGuardEnabled() || event.getTo() == null || !isOutOfBounds(event.getTo())) {
            return;
        }
        if (plugin.rigelConfig().movementGuardAlertOnly()) {
            alert(event.getPlayer());
            return;
        }
        World world = event.getTo().getWorld() != null ? event.getTo().getWorld() : event.getPlayer().getWorld();
        event.setTo(spawnOf(world));
        event.getPlayer().sendMessage(OUT_OF_BOUNDS_MESSAGE);
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        if (!plugin.rigelConfig().movementGuardEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!isOutOfBounds(player.getLocation())) {
            return;
        }
        if (plugin.rigelConfig().movementGuardAlertOnly()) {
            alert(player);
            return;
        }
        player.teleport(spawnOf(player.getWorld()));
        player.sendMessage(OUT_OF_BOUNDS_MESSAGE);
    }

    private void applyBorder(World world) {
        long radius = plugin.rigelConfig().worldBorderRadius();
        if (radius <= 0) {
            return;
        }
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(radius * 2.0);
    }

    private boolean isOutOfBounds(Location location) {
        long radius = plugin.rigelConfig().worldBorderRadius();
        return radius > 0 && (Math.abs(location.getX()) > radius || Math.abs(location.getZ()) > radius);
    }

    private static Location spawnOf(World world) {
        return world.getSpawnLocation();
    }

    private void alert(Player player) {
        AntiGriefSupport.notifyStaff(permissionGate, Component.text(
                "[Movement] " + player.getName() + " is outside the configured world border.",
                NamedTextColor.YELLOW));
    }
}
