package org.rigelmc.protect.worldedit.extent;

import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.protect.worldedit.WorldEditAbuseGuard;

/**
 * Extent-level defense-in-depth against blocked block types - non-staff only (see
 * {@link RigelEditExtentChain}). {@link org.rigelmc.protect.worldedit.WorldEditAbuseGuard}
 * already regex-matches the raw command text for these at the command-preprocess layer, but
 * that can miss anything not spelled out literally on the command line (a saved WorldEdit
 * clipboard/schematic pasted via {@code //paste}, a {@code .schematic} import, a script
 * function, ...). This extent re-checks every single block actually written, using the
 * exact same {@link WorldEditAbuseGuard#normalize(String)} definition of "what a blocked-
 * type entry matches" so the two layers can never disagree about what's blocked.
 */
final class BlockedTypeExtent extends RigelPathCompleteExtent {

    private final RigelMCMod plugin;
    private final UUID actorUuid;
    private final Set<String> blockedNormalized;
    private boolean warned;

    BlockedTypeExtent(
            @NotNull Extent parent, @NotNull RigelMCMod plugin, @NotNull UUID actorUuid,
            @NotNull Set<String> blockedNormalized) {
        super(parent);
        this.plugin = plugin;
        this.actorUuid = actorUuid;
        this.blockedNormalized = blockedNormalized;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block) throws WorldEditException {
        BlockType type = block.getBlockType();
        if (type != null && blockedNormalized.contains(WorldEditAbuseGuard.normalize(type.id()))) {
            warnOnce();
            throw new MaxChangedBlocksException(0);
        }
        return super.setBlock(pos, block);
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
                        "That WorldEdit operation references a blocked block type - halted.",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        });
    }
}
