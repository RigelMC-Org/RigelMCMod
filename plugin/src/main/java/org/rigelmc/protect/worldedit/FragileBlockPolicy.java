package org.rigelmc.protect.worldedit;

import com.sk89q.worldedit.world.block.BlockCategories;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import org.jetbrains.annotations.NotNull;

/**
 * Classifies a block as "fragile" - one that cannot support itself, so the moment whatever
 * it is attached to changes it pops off into a dropped item.
 *
 * <p>User-requested. This is the same attack class as the gravity blocks already listed in
 * {@code protect.worldedit.blocked-block-types}, just the mirror image of it: sand/gravel/
 * anvils convert into a mass {@code FallingBlock} spawn when gravity triggers, while
 * saplings, carpets, torches, flowers, redstone and rails convert into a mass {@code Item}
 * spawn when their support goes. A {@code //set torch} across a large region is a lag bomb
 * primed on the floor underneath it.
 *
 * <p><b>Deliberately no hand-maintained block list.</b> WorldEdit's own {@link
 * BlockMaterial#isFragileWhenPushed()} is Minecraft's {@code PushReaction.DESTROY} - exactly
 * "this block breaks into an item rather than moving" - and {@link BlockCategories} is backed
 * by Minecraft's own block tags, so both track new blocks across versions for free. That
 * matters: {@code extent.ContainerLimitExtent}'s javadoc already argued against TFM's
 * equivalent "hand-maintained block-id/suffix list ... needs updating every Minecraft
 * version" approach, and this honours that position rather than quietly reversing it. It is
 * also the same shape as the flag backing that class - {@code BlockMaterial#hasContainer()}.
 *
 * <p>Operates on {@link BlockStateHolder} rather than {@link BlockType} because {@code
 * BlockCategory#contains} is declared over it, and it is what the extent already has in
 * hand - so no {@code BukkitAdapter} and no Bukkit {@code Material} crossing is needed
 * (nothing in the extent package has ever needed one).
 *
 * <p>The actual boolean decision lives in {@link FragileBlockRule}, which has no WorldEdit
 * imports - see its javadoc for why that split is required rather than stylistic.</p>
 */
public final class FragileBlockPolicy {

    private FragileBlockPolicy() {
    }

    /**
     * The high-risk subset, banned outright rather than merely capped: on top of dropping as
     * items these drive redstone tick churn or fire on entity contact, so even a modest
     * number of them is disproportionately expensive.
     */
    public static <B extends BlockStateHolder<B>> boolean isBannedHighRisk(@NotNull B block) {
        if (BlockCategories.RAILS.contains(block) || BlockCategories.PRESSURE_PLATES.contains(block)) {
            return true;
        }
        BlockType type = block.getBlockType();
        return type == BlockTypes.REDSTONE_WIRE
                || type == BlockTypes.REPEATER
                || type == BlockTypes.COMPARATOR
                || type == BlockTypes.TRIPWIRE
                || type == BlockTypes.TRIPWIRE_HOOK;
    }

    /**
     * The broad "pops into an item when its support changes" category, which the caller caps
     * per operation. {@link #isBannedHighRisk} is a strict subset of this, so test that
     * first.
     */
    public static <B extends BlockStateHolder<B>> boolean isFragile(@NotNull B block) {
        BlockType type = block.getBlockType();
        if (type == null) {
            return false;
        }
        BlockMaterial material = type.getMaterial();
        if (material == null) {
            return false;
        }
        return FragileBlockRule.isFragile(
                material.isFragileWhenPushed(), material.isSolid(), material.isAir(), material.isLiquid());
    }
}
