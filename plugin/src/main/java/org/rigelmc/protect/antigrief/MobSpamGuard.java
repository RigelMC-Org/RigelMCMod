package org.rigelmc.protect.antigrief;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * Two related concerns bundled together since both are "stop the mob population from
 * spiralling":
 *
 * <ul>
 *   <li><b>No natural spawns at all</b> - cancels every {@link CreatureSpawnEvent} whose
 *       {@link CreatureSpawnEvent.SpawnReason} is {@code NATURAL} (the standard
 *       darkness/biome ambient spawn, hostile or passive alike) when enabled - a deliberate
 *       server-wide policy choice, not a rate limit. Spawner-triggered, bred, egg-spawned,
 *       dispensed, and plugin-spawned ({@code CUSTOM}) mobs are all untouched - this only
 *       targets ambient wild spawning.</li>
 *   <li><b>Zero spawning except {@code /summon}/{@code /spawnmob}</b> - a stricter, opt-in
 *       superset of the above (see {@link org.rigelmc.core.RigelConfig#onlySummonSpawnsAllowed}):
 *       cancels every {@code CreatureSpawnEvent} whose reason isn't {@code COMMAND} - natural,
 *       spawner, breeding, egg (including dispensed), and any other plugin-spawned mob are
 *       all blocked, not just ambient natural spawning. Vanilla {@code /summon} and {@code
 *       fun.SpawnMobModule}'s {@code /spawnmob} both produce {@code COMMAND}-reasoned spawns,
 *       so both stay working either way.</li>
 *   <li><b>Per-type spawn cooldown + per-world population cap</b> - applies to every
 *       {@code CreatureSpawnEvent} regardless of reason (mainly relevant to
 *       spawner-triggered spawns once natural spawning is off), defending against a
 *       spawner-spam grief flooding a world with mobs. TFM ref: MobBlocker.java,
 *       EventBlocker.java.</li>
 * </ul>
 *
 * Reads {@code plugin.rigelConfig()} fresh on every event - see {@link AntiNukeGuard}'s
 * javadoc for why.
 */
public final class MobSpamGuard implements Listener {

    private final RigelMCMod plugin;
    private final Map<EntityType, Long> lastSpawnAtMillis = new ConcurrentHashMap<>();

    public MobSpamGuard(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(@NotNull CreatureSpawnEvent event) {
        if (plugin.rigelConfig().disableNaturalMobSpawns()
                && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
            event.setCancelled(true);
            return;
        }
        if (plugin.rigelConfig().onlySummonSpawnsAllowed()
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.COMMAND) {
            event.setCancelled(true);
            return;
        }

        if (!plugin.rigelConfig().mobSpamGuardEnabled()) {
            return;
        }

        LivingEntity entity = event.getEntity();
        World world = event.getLocation().getWorld();
        if (world != null && countLivingNonPlayers(world) >= plugin.rigelConfig().mobSpamMaxPerWorld()) {
            event.setCancelled(true);
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = plugin.rigelConfig().mobSpamPerTypeCooldownSeconds() * 1000L;
        Long last = lastSpawnAtMillis.get(entity.getType());
        if (cooldownMillis > 0 && last != null && now - last < cooldownMillis) {
            event.setCancelled(true);
            return;
        }
        lastSpawnAtMillis.put(entity.getType(), now);
    }

    private static int countLivingNonPlayers(World world) {
        int count = 0;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof LivingEntity && !(entity instanceof Player)) {
                count++;
            }
        }
        return count;
    }
}
