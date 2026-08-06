package org.rigelmc.protect.crash;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.entity.TrialSpawnerSpawnEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * Blocks a mob spawner (or trial spawner) from ever producing a "hanging" entity type -
 * paintings, item frames, and leash knots have no legitimate reason to come out of a
 * spawner, and can be abused to overload a chunk with hitbox-heavy entities or crash a
 * client. TFM ref: {@code SpawnerValidator.java}. No staff exemption, unlike this
 * package's other guards - this isn't a punishment, it's a client-crash defense that
 * applies regardless of who placed the spawner.
 */
public final class SpawnerGuard implements Listener {

    private static final Set<EntityType> HANGING_TYPES =
            EnumSet.of(EntityType.PAINTING, EntityType.ITEM_FRAME, EntityType.GLOW_ITEM_FRAME, EntityType.LEASH_KNOT);

    private final RigelMCMod plugin;

    public SpawnerGuard(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnerSpawn(@NotNull SpawnerSpawnEvent event) {
        if (!plugin.rigelConfig().crashSpawnerGuardEnabled()) {
            return;
        }
        if (HANGING_TYPES.contains(event.getEntity().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTrialSpawnerSpawn(@NotNull TrialSpawnerSpawnEvent event) {
        if (!plugin.rigelConfig().crashSpawnerGuardEnabled()) {
            return;
        }
        if (HANGING_TYPES.contains(event.getEntity().getType())) {
            event.setCancelled(true);
        }
    }
}
