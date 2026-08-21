package org.rigelmc.world;

import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * User-requested: "on join, make sure player spawns in flatlands, do not allow in regular
 * world." Keeps every non-staff player out of the primary Overworld entirely - they join
 * into, respawn into, and are bounced back to the flatlands sandbox instead.
 *
 * <p>"The regular world" is {@code Bukkit.getWorlds().get(0)}, the same primary-Overworld
 * convention {@link RegularWorldWipeService} and {@link FlatlandsService} already use. The
 * Nether and End are deliberately <b>not</b> covered - they are separate worlds, and {@code
 * /world nether|end} exists specifically to let players reach them; only the Overworld
 * itself is off-limits. The flatlands sandbox, guild plot world, and admin world are all
 * likewise untouched.</p>
 *
 * <p>Staff bypass ({@code world.main-world-lockdown.bypass-rank}, default {@code moderator})
 * - without it nobody could moderate the main world, and {@code /wipeworld}/{@code
 * /setspawn} would be unusable there.</p>
 *
 * <p><b>Layered deliberately</b>, matching the {@link AdminWorldTeleportGuard} + {@link
 * AdminWorldPresenceGuard} pair this is modelled on, because no single hook is provably
 * enough:</p>
 * <ul>
 *   <li>{@link #onTeleport} - proactive, and covers portals for free ({@code
 *       PlayerPortalEvent} extends {@code PlayerTeleportEvent}), so a Nether portal leading
 *       back to the Overworld lands in flatlands instead. <b>Redirects rather than
 *       cancels</b>: cancelling a portal teleport leaves the player standing in the portal,
 *       which simply re-fires it in a loop.</li>
 *   <li>{@link #onRespawn} - a death respawn is not a teleport, so it needs its own hook (a
 *       bed or the world spawn point would otherwise put them right back).</li>
 *   <li>{@link #onChangedWorld} - fires for <i>any</i> means of changing world, including
 *       the vehicle-riding case Bukkit does not reliably fire a teleport event for (a real,
 *       previously-reported exploit against the admin world - see {@link
 *       AdminWorldPresenceGuard}).</li>
 *   <li>{@link #sweep()} - periodic backstop for anything that bypasses every event hook.</li>
 * </ul>
 *
 * <p>If the flatlands world is not loaded there is nowhere safe to send anyone, so this
 * does nothing at all rather than strand a player mid-air or in an unloaded world - the
 * same "degrade to a no-op rather than break" stance {@link SpawnJoinListener}'s own
 * flatlands fallback takes.</p>
 */
public final class MainWorldLockdownGuard implements Listener {

    private static final long SWEEP_INTERVAL_TICKS = 100L; // 5s, matching AdminWorldPresenceGuard

    /**
     * Same race, same fix as {@link AdminWorldPresenceGuard}'s own join delay, and it matters
     * more here: {@code core.PlayerLoginListener} populates {@link PermissionGate}'s online-
     * rank cache asynchronously, and Bukkit runs every {@code PlayerJoinEvent} listener
     * back-to-back without waiting for that. Checking immediately would read an empty cache
     * for every joining player and boot legitimate staff out of the main world on login.
     */
    private static final long JOIN_CHECK_DELAY_TICKS = 20L; // 1 second

    private final FlatlandsService flatlandsService;
    private final PermissionGate permissionGate;
    private RigelMCMod plugin;

    public MainWorldLockdownGuard(
            @NotNull FlatlandsService flatlandsService, @NotNull PermissionGate permissionGate) {
        this.flatlandsService = flatlandsService;
        this.permissionGate = permissionGate;
    }

    /** Starts the periodic sweep. Call once from {@code WorldModule#registerListeners}. */
    public void start(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(@NotNull PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || !isLockedDown(to.getWorld()) || mayEnter(event.getPlayer())) {
            return;
        }
        flatlandsSpawn().ifPresent(destination -> {
            event.setTo(destination);
            notifyBlocked(event.getPlayer());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(@NotNull PlayerRespawnEvent event) {
        Location to = event.getRespawnLocation();
        if (!isLockedDown(to.getWorld()) || mayEnter(event.getPlayer())) {
            return;
        }
        flatlandsSpawn().ifPresent(event::setRespawnLocation);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        eject(event.getPlayer());
    }

    /**
     * A player logging in with their last-known location already in the main world is a
     * first-time world assignment, not a change, so {@link #onChangedWorld} never sees it -
     * this is the hook that delivers the "on join, spawn in flatlands" half of the request.
     * Deliberately delayed - see {@link #JOIN_CHECK_DELAY_TICKS}.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player stillOnline = Bukkit.getPlayer(uuid);
            if (stillOnline != null) {
                eject(stillOnline);
            }
        }, JOIN_CHECK_DELAY_TICKS);
    }

    private void sweep() {
        World mainWorld = Bukkit.getWorlds().get(0);
        if (!isLockedDown(mainWorld)) {
            return;
        }
        for (Player player : mainWorld.getPlayers()) {
            eject(player);
        }
    }

    /** No-op unless this player is actually standing in the locked-down main world without a bypass. */
    private void eject(@NotNull Player player) {
        if (!isLockedDown(player.getWorld()) || mayEnter(player)) {
            return;
        }
        Optional<Location> destination = flatlandsSpawn();
        if (destination.isEmpty()) {
            return; // flatlands not ready - better to leave them put than strand them
        }

        // Dismount first - a mounted player cannot be reliably relocated, and removing the
        // vehicle closes the "stay in a boat" cross-world evasion AdminWorldPresenceGuard
        // documents as a real, previously-reported exploit against the admin world.
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            vehicle.eject();
            if (vehicle instanceof Vehicle) {
                vehicle.remove();
            }
        }
        player.teleport(destination.get());
        notifyBlocked(player);
    }

    private boolean isLockedDown(World world) {
        return world != null
                && plugin != null
                && plugin.rigelConfig().mainWorldLockdownEnabled()
                && world.equals(Bukkit.getWorlds().get(0));
    }

    /** @return {@code true} if this player is staff enough to be in the main world. */
    private boolean mayEnter(@NotNull Player player) {
        return permissionGate.hasAtLeastCached(
                player.getUniqueId(), plugin.rigelConfig().mainWorldLockdownBypassRank());
    }

    @NotNull
    private Optional<Location> flatlandsSpawn() {
        return flatlandsService.world().map(World::getSpawnLocation);
    }

    private static void notifyBlocked(@NotNull Player player) {
        player.sendMessage(Component.text(
                "The main world is closed - you have been sent to the flatlands.", NamedTextColor.YELLOW));
    }
}
