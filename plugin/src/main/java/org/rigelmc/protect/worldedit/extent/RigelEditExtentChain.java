package org.rigelmc.protect.worldedit.extent;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.protect.area.ProtectAreaService;
import org.rigelmc.protect.worldedit.WorldEditAbuseGuard;
import org.rigelmc.protect.worldedit.WorldEditLimitOverrideService;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.world.SpawnService;

/**
 * Assembles and installs the {@link Extent} chain wrapped around every WorldEdit/FAWE edit
 * session - the {@code EditSessionEvent} subscriber's actual body, called from {@code
 * WorldEditExtentService}. Mirrors TFM's real {@code onEditSession} logic (studied directly
 * in {@code WorldEditHook.java}) with one structural difference: {@link ProtectAreaExtent}
 * always wraps, for staff and non-staff alike (see its own javadoc for why), where TFM's
 * {@code ProtectedAreaExtent} is unconditionally added too but relies entirely on its own
 * internal {@code isAdmin} short-circuit for staff exemption - the net bypass behavior ends
 * up equivalent, but ours is driven by {@link ProtectAreaService#isBypassing}'s richer
 * per-region owner/member check rather than a single global admin flag.
 */
public final class RigelEditExtentChain {

    private RigelEditExtentChain() {
    }

    /**
     * @param event the raw subscribed event - filtered here to {@link
     *     EditSession.Stage#BEFORE_CHANGE} (matching TFM exactly: the other stage, {@code
     *     BEFORE_REORDER}, is a WorldEdit-internal optimization pass this project has no
     *     reason to also wrap) and to a real {@link Player} actor (skips console/plugin-
     *     driven sessions, which have no player to attribute limits or bypass checks to)
     */
    public static void wrap(
            @NotNull EditSessionEvent event, @NotNull RigelMCMod plugin,
            @NotNull ProtectAreaService protectAreaService, @NotNull PermissionGate permissionGate,
            @NotNull WorldEditLimitOverrideService limitOverrideService, @NotNull SpawnService spawnService) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }
        Actor actor = event.getActor();
        if (!(actor instanceof Player wePlayer)) {
            return;
        }

        UUID uuid = wePlayer.getUniqueId();
        String world = event.getWorld().getName();

        // Always wraps - protection bypass is per-region (owner/member/Moderator+), not a
        // blanket "no checks for staff at all."
        Extent wrapped = new ProtectAreaExtent(event.getExtent(), plugin, protectAreaService, uuid, world);

        if (!permissionGate.hasAtLeastCached(uuid, "moderator")) {
            int volumeMax = limitOverrideService.effectiveVolumeMax(plugin.rigelConfig().worldEditMaxVolume());
            wrapped = new VolumeLimitExtent(wrapped, plugin, uuid, volumeMax);

            int containerCap = plugin.rigelConfig().worldEditMaxContainers();
            if (containerCap >= 0) {
                wrapped = new ContainerLimitExtent(wrapped, plugin, uuid, containerCap);
            }

            if (plugin.rigelConfig().worldEditFragileBlocksEnabled()) {
                wrapped = new FragileBlockExtent(
                        wrapped, plugin, uuid,
                        plugin.rigelConfig().worldEditFragileBanHighRisk(),
                        plugin.rigelConfig().worldEditFragileMaxPerOperation());
            }

            Set<String> blocked = normalizedBlockedTypes(plugin);
            if (!blocked.isEmpty()) {
                wrapped = new BlockedTypeExtent(wrapped, plugin, uuid, blocked);
            }

            wrapped = maybeWrapSpawnProtection(wrapped, plugin, spawnService, uuid, world);
        }

        event.setExtent(wrapped);
    }

    /**
     * User-requested: the deeper, per-block-position half of spawn protection - see
     * {@code WorldEditAbuseGuard#checkRadius}'s javadoc for the command-preprocess-layer
     * half. No-ops (returns {@code wrapped} untouched) unless spawn-protection is
     * enabled, {@code /setspawn} has actually been run, and this edit's world matches
     * the spawn's world - comparing by world <i>name</i> (matching {@link
     * ProtectAreaExtent}'s own {@code world} field, a plain {@link String}), since {@code
     * event.getWorld()} is a WorldEdit {@code World}, not a Bukkit one.
     */
    private static Extent maybeWrapSpawnProtection(
            Extent wrapped, RigelMCMod plugin, SpawnService spawnService, UUID uuid, String world) {
        RigelConfig config = plugin.rigelConfig();
        if (!config.worldEditSpawnProtectionEnabled()) {
            return wrapped;
        }
        Optional<Location> spawn = spawnService.spawnLocation();
        if (spawn.isEmpty() || spawn.get().getWorld() == null || !spawn.get().getWorld().getName().equals(world)) {
            return wrapped;
        }
        Location spawnLocation = spawn.get();
        BlockVector3 spawnVector =
                BlockVector3.at(spawnLocation.getBlockX(), spawnLocation.getBlockY(), spawnLocation.getBlockZ());
        return new SpawnProtectionExtent(
                wrapped, plugin, uuid, spawnVector, config.worldEditSpawnProtectionRadius(),
                config.worldEditSpawnProtectionMaxBlocks());
    }

    private static Set<String> normalizedBlockedTypes(RigelMCMod plugin) {
        Set<String> normalized = new HashSet<>();
        for (String type : plugin.rigelConfig().worldEditBlockedBlockTypes()) {
            normalized.add(WorldEditAbuseGuard.normalize(type));
        }
        return normalized;
    }
}
