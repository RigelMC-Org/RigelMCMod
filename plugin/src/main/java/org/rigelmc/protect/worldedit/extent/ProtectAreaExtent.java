package org.rigelmc.protect.worldedit.extent;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.protect.area.AreaFlag;
import org.rigelmc.protect.area.AreaRegion;
import org.rigelmc.protect.area.ProtectAreaService;

/**
 * The core deliverable of this whole extent chain: enforces {@code /protectarea}'s {@link
 * AreaFlag#BUILD} flag against live WorldEdit/FAWE edits, closing the exact gap Bukkit-event
 * protection (already built - see {@code protect.area.AreaProtectionListener}) can never
 * close on its own, since WorldEdit writes blocks through its own {@code Extent} pipeline
 * without firing {@code BlockBreakEvent}/{@code BlockPlaceEvent} at all.
 *
 * <p><b>Always wraps</b>, staff and non-staff alike - unlike the three abuse-cap extents
 * ({@link VolumeLimitExtent}, {@link ContainerLimitExtent}, {@link BlockedTypeExtent}), which
 * {@link RigelEditExtentChain} only adds around non-staff sessions. Protection bypass is
 * decided per-region ({@link ProtectAreaService#isBypassing}, which already folds in a
 * Moderator+ floor), not by a blanket "no checks at all for staff" the way TFM's real
 * {@code ProtectedAreaExtent} short-circuits on {@code isAdmin(player)} before ever touching
 * region data.</p>
 *
 * <p><b>Per-block, not per-session</b> - the concrete improvement over TFM's real {@code
 * ProtectedAreaExtent}, which lazily checks <i>once</i> (on the first written block) whether
 * the edit's running bounding box overlaps any protected area, then applies that single
 * verdict to every block for the rest of the operation. That means a TFM operation spanning
 * both protected and unprotected space either sails through entirely (if the check fires
 * before the box ever touches the region) or is denied entirely (including blocks nowhere
 * near the region, once it has). This class instead resolves {@link
 * ProtectAreaService#effectiveRegionAt} independently for every single block position, so a
 * {@code //replace} or brush stroke that only partially overlaps a region is denied exactly
 * where it overlaps and allowed everywhere else.</p>
 */
final class ProtectAreaExtent extends RigelPathCompleteExtent {

    private final RigelMCMod plugin;
    private final ProtectAreaService protectAreaService;
    private final UUID actorUuid;
    private final String world;
    private boolean warned;

    ProtectAreaExtent(
            @NotNull Extent parent, @NotNull RigelMCMod plugin, @NotNull ProtectAreaService protectAreaService,
            @NotNull UUID actorUuid, @NotNull String world) {
        super(parent);
        this.plugin = plugin;
        this.protectAreaService = protectAreaService;
        this.actorUuid = actorUuid;
        this.world = world;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block) throws WorldEditException {
        if (isDenied(pos)) {
            warnOnce();
            return false;
        }
        return super.setBlock(pos, block);
    }

    private boolean isDenied(BlockVector3 pos) {
        Optional<AreaRegion> region = protectAreaService.effectiveRegionAt(world, pos.x(), pos.y(), pos.z());
        if (region.isEmpty() || region.get().effectiveFlag(AreaFlag.BUILD)) {
            return false;
        }
        Player player = Bukkit.getPlayer(actorUuid);
        return player == null || !protectAreaService.isBypassing(player, region.get());
    }

    private void warnOnce() {
        if (warned) {
            return;
        }
        warned = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(actorUuid);
            if (player != null) {
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        "Some blocks were skipped - part of that operation overlaps a protected area.",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        });
    }
}
