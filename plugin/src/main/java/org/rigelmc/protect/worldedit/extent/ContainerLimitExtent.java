package org.rigelmc.protect.worldedit.extent;

import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * Per-session container-block (chests, hoppers, furnaces, ...) placement cap - non-staff
 * only (see {@link RigelEditExtentChain}). Matches TFM's own {@code ContainerLimitExtent},
 * with one deliberate scope reduction: TFM also ships a <i>separate</i> {@code
 * TileLimitExtent} that additionally catches non-container tile-entities (signs, skulls,
 * banners, ...) via a hand-maintained block-id/suffix list - WorldEdit's public {@link
 * BlockMaterial} exposes no broader "is this a tile-entity" signal than {@link
 * BlockMaterial#hasContainer()} to check against without that fragile, needs-updating-
 * every-Minecraft-version list, so this class covers containers only.
 */
final class ContainerLimitExtent extends RigelPathCompleteExtent {

    private final RigelMCMod plugin;
    private final UUID actorUuid;
    private final int cap;
    private final AtomicInteger count = new AtomicInteger();
    private boolean warned;

    ContainerLimitExtent(@NotNull Extent parent, @NotNull RigelMCMod plugin, @NotNull UUID actorUuid, int cap) {
        super(parent);
        this.plugin = plugin;
        this.actorUuid = actorUuid;
        this.cap = cap;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block) throws WorldEditException {
        BlockType type = block.getBlockType();
        if (type != null) {
            BlockMaterial material = type.getMaterial();
            if (material != null && material.hasContainer() && count.incrementAndGet() > cap) {
                warnOnce();
                throw new MaxChangedBlocksException(cap);
            }
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
                        "WorldEdit container limit reached (" + cap + ") - operation halted.",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        });
    }
}
