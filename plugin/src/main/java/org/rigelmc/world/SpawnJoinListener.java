package org.rigelmc.world;

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
 */
public final class SpawnJoinListener implements Listener {

    private final RigelMCMod plugin;
    private final SpawnService spawnService;

    public SpawnJoinListener(@NotNull RigelMCMod plugin, @NotNull SpawnService spawnService) {
        this.plugin = plugin;
        this.spawnService = spawnService;
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
            if (player.isOnline()) {
                spawnService.spawnLocation().ifPresent(player::teleport);
            }
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
