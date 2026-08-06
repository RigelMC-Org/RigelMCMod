package org.rigelmc.protect.worldedit.extent;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.Extent;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.protect.area.ProtectAreaService;
import org.rigelmc.protect.worldedit.WorldEditAbuseGuard;
import org.rigelmc.rank.PermissionGate;

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
            @NotNull ProtectAreaService protectAreaService, @NotNull PermissionGate permissionGate) {
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
            wrapped = new VolumeLimitExtent(wrapped, plugin, uuid, plugin.rigelConfig().worldEditMaxVolume());

            int containerCap = plugin.rigelConfig().worldEditMaxContainers();
            if (containerCap >= 0) {
                wrapped = new ContainerLimitExtent(wrapped, plugin, uuid, containerCap);
            }

            Set<String> blocked = normalizedBlockedTypes(plugin);
            if (!blocked.isEmpty()) {
                wrapped = new BlockedTypeExtent(wrapped, plugin, uuid, blocked);
            }
        }

        event.setExtent(wrapped);
    }

    private static Set<String> normalizedBlockedTypes(RigelMCMod plugin) {
        Set<String> normalized = new HashSet<>();
        for (String type : plugin.rigelConfig().worldEditBlockedBlockTypes()) {
            normalized.add(WorldEditAbuseGuard.normalize(type));
        }
        return normalized;
    }
}
