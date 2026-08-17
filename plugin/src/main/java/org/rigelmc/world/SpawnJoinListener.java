package org.rigelmc.world;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * Sends joining/respawning players to the configured spawn ({@link SpawnService}), per the
 * {@code world.spawn.send-on-join}/{@code send-on-respawn} config policy - TFM-style
 * ({@code SpawnManager}'s own join/respawn handlers). Deliberately split out of {@code
 * core.PlayerLoginListener}: that listener's job is identity/rank/display-state
 * resolution, this one's is purely "where does the player end up," and the two concerns
 * shouldn't be coupled.
 *
 * <p>User-requested: a genuinely first-time joiner with no RMCM spawn configured yet
 * ({@code /setspawn} never run) must never be silently left at vanilla's own primary-world
 * spawn - see {@link #onJoin}'s flatlands fallback.</p>
 */
public final class SpawnJoinListener implements Listener {

    private final RigelMCMod plugin;
    private final SpawnService spawnService;
    private final FlatlandsService flatlandsService;

    public SpawnJoinListener(
            @NotNull RigelMCMod plugin, @NotNull SpawnService spawnService, @NotNull FlatlandsService flatlandsService) {
        this.plugin = plugin;
        this.spawnService = spawnService;
        this.flatlandsService = flatlandsService;
    }

    /**
     * {@code LOWEST} priority so other plugins' own join handling sees the player already
     * settled at spawn, not their raw world-saved location - matches TFM's own ordering.
     * The teleport itself is deferred one tick via the scheduler (also matching TFM):
     * teleporting a player in the exact same tick their client receives the join packet
     * can occasionally be silently ignored.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        String policy = plugin.rigelConfig().spawnSendOnJoin();
        if ("never".equalsIgnoreCase(policy)) {
            return;
        }
        boolean firstJoin = !event.getPlayer().hasPlayedBefore();
        if ("first_join".equalsIgnoreCase(policy) && !firstJoin) {
            return;
        }

        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Optional<Location> destination = spawnService.spawnLocation();
            if (destination.isEmpty() && firstJoin) {
                // User-requested: a brand-new player with no RMCM spawn configured yet must
                // never be left at vanilla's own primary-world spawn - default them into the
                // flatlands sandbox instead. Scoped to a genuine first join only (not every
                // "always"-policy join with no spawn set), so a returning player already
                // playing in the main world is never silently redirected out of it. If
                // flatlands itself isn't loaded yet (initializeWorld's async creation hasn't
                // finished - see FlatlandsService#initializeWorld's javadoc), this stays
                // empty too and the player is left exactly where vanilla put them, same
                // graceful no-op as before this fallback existed.
                destination = flatlandsService.world().map(World::getSpawnLocation);
            }
            destination.ifPresent(player::teleport);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(@NotNull PlayerRespawnEvent event) {
        if (!plugin.rigelConfig().spawnSendOnRespawn()) {
            return;
        }
        spawnService.spawnLocation().ifPresent(event::setRespawnLocation);
    }
}
