package org.rigelmc.world;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * Reactive safety net + periodic sweep behind {@link AdminWorldTeleportGuard} - user-
 * reported, real incident: a non-staff player used another plugin's world-teleport
 * command ({@code /eworld adminworld}) while riding a boat to reach the admin world
 * anyway ("spawn a boat, stay in that boat"). {@link AdminWorldTeleportGuard}'s own
 * javadoc claimed {@code PlayerTeleportEvent} alone "covers ... any other plugin's
 * teleport alike, ... in a single place" - that turned out to be wrong in practice:
 * Bukkit does not reliably fire {@code PlayerTeleportEvent} for a player riding a vehicle
 * that itself gets teleported/moved cross-world, which is exactly the gap "stay in that
 * boat" describes exploiting. TFM has this same reported gap; not replicated here.
 *
 * <p>Two layers, matching this codebase's established "one hook isn't provably enough"
 * pattern elsewhere (see {@code protect.antigrief.MovementBoundsGuard}'s own move+
 * teleport+join trio for the world border):
 * <ul>
 *   <li>{@link #onChangedWorld} - fires for ANY means of ending up in a different world,
 *       vehicle-involved or not, unlike a teleport-specific hook.</li>
 *   <li>{@link #sweep()} - a periodic check of everyone currently in the admin world, the
 *       final backstop for anything that slips past every event hook entirely (e.g. a
 *       plugin moving a player via raw NMS/packet manipulation with no Bukkit event at
 *       all - the same class of gap {@code MovementBoundsGuard} accepts a hook can't
 *       fully close, hence its own belt-and-suspenders approach).</li>
 * </ul></p>
 */
public final class AdminWorldPresenceGuard implements Listener {

    private static final long SWEEP_INTERVAL_TICKS = 100L; // 5 seconds - frequent enough that a boat-riding evasion can't sit there long

    /**
     * User-reported, real incident this delay fixes: {@code core.PlayerLoginListener}'s
     * own {@code PlayerJoinEvent} handler populates {@code rank.PermissionGate}'s online-
     * rank cache asynchronously (a DB round-trip, then a hop back to the main thread) -
     * Bukkit fires every {@code PlayerJoinEvent} listener back-to-back in the same
     * synchronous dispatch pass regardless of priority, it does not wait for one
     * listener's async work to finish before running the next. Checking {@link
     * #eject} immediately in {@link #onJoin} therefore reads that cache before it's
     * populated for every single joining player, false-positive-ejecting a legitimate
     * Senior Admin whose last-known location happened to be inside the admin world. A
     * short delay gives that async population time to land first; {@link #sweep()}
     * (every 5s) is still the backstop if it somehow hasn't by then.
     */
    private static final long JOIN_CHECK_DELAY_TICKS = 20L; // 1 second

    private final AdminWorldService adminWorldService;
    private RigelMCMod plugin;

    public AdminWorldPresenceGuard(@NotNull AdminWorldService adminWorldService) {
        this.adminWorldService = adminWorldService;
    }

    /** Starts the periodic sweep. Call once from {@code WorldModule#registerListeners}. */
    public void start(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        eject(event.getPlayer());
    }

    /**
     * {@link PlayerChangedWorldEvent} only fires on an in-session world <i>change</i> - a
     * player logging in with their last-known location already inside the admin world
     * (e.g. a prior exploit, or a bugged plugin setting it) is a first-time world
     * assignment, not a change, so it needs its own check. Deliberately delayed rather
     * than immediate - see {@link #JOIN_CHECK_DELAY_TICKS}'s own javadoc.
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
        adminWorldService.world().ifPresent(world -> {
            for (Player player : world.getPlayers()) {
                eject(player);
            }
        });
    }

    /** No-op if {@code player} isn't currently in the admin world, or is legitimately allowed there. */
    private void eject(@NotNull Player player) {
        World adminWorld = adminWorldService.world().orElse(null);
        if (adminWorld == null || !player.getWorld().equals(adminWorld)
                || adminWorldService.canAccess(player.getUniqueId())) {
            return;
        }

        // Dismount first - a mounted player can't be reliably relocated otherwise, and
        // removing the vehicle (rather than just ejecting from it) closes off exactly the
        // "stay in that boat" evasion this class exists to stop.
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            vehicle.eject();
            vehicle.remove();
        }
        // Also sweep up anything they placed and dismounted from in the split-second
        // before this fired (right-click-spamming a boat, then being ejected from it
        // separately) - user-reported as the actual observed exploit shape. The admin
        // world is staff-only, so an abandoned, unridden boat/vehicle near a just-caught
        // unauthorized player is virtually certainly this exact artifact, not legitimate.
        for (Entity nearby : player.getNearbyEntities(4, 4, 4)) {
            if (nearby instanceof Vehicle && nearby.getPassengers().isEmpty()) {
                nearby.remove();
            }
        }

        World safeWorld = Bukkit.getWorlds().get(0);
        player.teleport(safeWorld.getSpawnLocation());
        player.sendMessage(
                Component.text("You don't have permission to be in the admin world.", NamedTextColor.RED));
    }
}
