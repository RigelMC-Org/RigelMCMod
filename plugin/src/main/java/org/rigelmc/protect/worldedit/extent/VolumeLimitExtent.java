package org.rigelmc.protect.worldedit.extent;

import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * Per-session block-write cap - non-staff only (see {@link RigelEditExtentChain}), matches
 * TFM's own {@code LimitExtent}. Throwing {@link MaxChangedBlocksException} is WorldEdit's
 * own sanctioned way to halt an in-progress operation partway through - it isn't a bug
 * being propagated, it's the same mechanism {@code //limit} itself uses.
 */
final class VolumeLimitExtent extends RigelPathCompleteExtent {

    private final RigelMCMod plugin;
    private final UUID actorUuid;
    private final int limit;
    private final AtomicInteger count = new AtomicInteger();
    private boolean warned;

    VolumeLimitExtent(@NotNull Extent parent, @NotNull RigelMCMod plugin, @NotNull UUID actorUuid, int limit) {
        super(parent);
        this.plugin = plugin;
        this.actorUuid = actorUuid;
        this.limit = limit;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block) throws WorldEditException {
        if (count.incrementAndGet() > limit) {
            warnOnce();
            throw new MaxChangedBlocksException(limit);
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
                        "WorldEdit limit reached (" + limit + " blocks) - operation halted.",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        });
    }
}
