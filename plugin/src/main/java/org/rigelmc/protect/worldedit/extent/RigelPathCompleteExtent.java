package org.rigelmc.protect.worldedit.extent;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;

/**
 * Shared base for every extent in {@link RigelEditExtentChain} - TFM's own {@code
 * PathCompleteExtent}, ported verbatim (confirmed via direct read of its real {@code
 * WorldEditHook.java}, byte-verified against the actual pinned {@code worldedit-core
 * 7.3.10} jar). {@link AbstractDelegateExtent} only mandates the {@code
 * setBlock(BlockVector3, T)} overload; WorldEdit's own internal callers frequently use the
 * raw-int-coordinate overload instead, so every subclass needs this int-to-{@code
 * BlockVector3} forwarding or writes routed through that path would silently skip every
 * check below.
 */
abstract class RigelPathCompleteExtent extends AbstractDelegateExtent {

    RigelPathCompleteExtent(Extent parent) {
        super(parent);
    }

    // Deliberately not @Override - this overload isn't declared on AbstractDelegateExtent
    // or any interface it implements (only setBlock(BlockVector3, T) is), it's a new
    // overload some WorldEdit-internal callers use instead - matching TFM's own real
    // PathCompleteExtent exactly, which doesn't annotate this either.
    public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) throws WorldEditException {
        return setBlock(BlockVector3.at(x, y, z), block);
    }
}
