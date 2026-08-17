package org.rigelmc.protect.antigrief;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * User-reported real freeze: nothing in this codebase previously rate-limited how many
 * projectiles (ender pearls, snowballs, eggs, potions) a single player could launch per
 * second. {@code AntiNukeGuard} only covers block break/place, {@code MobSpamGuard} only
 * ever sees {@code CreatureSpawnEvent} (which projectiles never fire), and nothing else in
 * this package touches projectiles at all. Every live projectile runs a per-tick
 * nearby-entity collision scan ({@code ProjectileUtil#getHitResult} → {@code
 * Level#getEntities}) - flooding enough of them into flight at once, fast enough, overloads
 * that scan and freezes the main thread, confirmed by a real Watchdog thread dump showing
 * exactly that call chain stuck.
 *
 * <p>Modeled directly on {@link AntiDropGuard} (one {@link PerPlayerRateLimiter}, same
 * cheap per-event cost) but uses {@link AntiGriefSupport#applyAction} for a configurable
 * kick/alert-only action like {@link AntiNukeGuard}, rather than cancel-only - an unbounded
 * projectile flood is the more severe of the two (a full server freeze, not just clutter),
 * so it warrants the stronger default response.</p>
 *
 * <p>Only ever throttles a player's own throws - {@code ProjectileLaunchEvent#getEntity()
 * #getShooter()} must be a {@link Player}, so dispensers, skeletons, etc. launching their
 * own projectiles are never touched by this guard.</p>
 */
public final class ProjectileSpamGuard implements Listener {

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;
    private final PerPlayerRateLimiter<UUID> launches = new PerPlayerRateLimiter<>(1_000);

    public ProjectileSpamGuard(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLaunch(@NotNull ProjectileLaunchEvent event) {
        if (!plugin.rigelConfig().projectileSpamEnabled()
                || !(event.getEntity().getShooter() instanceof Player player)
                || permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator")) {
            return;
        }
        int count = launches.recordAndCount(player.getUniqueId(), System.currentTimeMillis());
        if (count > plugin.rigelConfig().projectileSpamMaxPerSecond()) {
            event.setCancelled(true);
            AntiGriefSupport.applyAction(
                    plugin.rigelConfig().projectileSpamAction(),
                    player,
                    permissionGate,
                    "[AntiNuke] " + player.getName() + " triggered the projectile-launch rate limit.",
                    "Disconnected: suspicious projectile activity detected.");
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        launches.clear(event.getPlayer().getUniqueId());
    }
}
