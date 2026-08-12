package org.rigelmc.guild.plot;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * Turns a {@link PlotCosmetic} into concrete block writes, and (separately) actually places
 * them. Placed via plain {@link org.bukkit.block.Block#setType}, deliberately never
 * WorldEdit - the plugin is placing these blocks itself, not a player, so {@code
 * BlockPlaceEvent} never fires and the plot's own {@code protect.area} protection has no
 * effect on it either way (that protection only ever intercepts player-driven events/edit
 * sessions - see {@code AreaProtectionListener}) - a real WorldEdit dependency would add
 * nothing here except complexity.
 *
 * <p>{@link #blockWritesFor} is pure geometry - no Bukkit dependency, fully unit-testable
 * (see {@code PlotCosmeticApplierTest}). {@link #apply} is the one Bukkit-touching method,
 * called only from {@code guild.GuildCommand} after a purchase/re-application succeeds.</p>
 */
public final class PlotCosmeticApplier {

    private PlotCosmeticApplier() {
    }

    /** One block to place - {@code y} is an absolute world coordinate, not relative to the plot. */
    public record BlockWrite(int x, int y, int z, @NotNull Material material) {
    }

    /**
     * @param bounds the plot's X/Z footprint (see {@link PlotGridAllocator})
     * @param groundY the plot's flat ground level ({@code guild.plotworld.ground-y})
     * @return every block this cosmetic needs written, in no particular order
     */
    @NotNull
    public static List<BlockWrite> blockWritesFor(
            @NotNull PlotCosmetic cosmetic, @NotNull PlotGridAllocator.PlotBounds bounds, int groundY) {
        return switch (cosmetic.category()) {
            case BORDER -> borderWrites(bounds, groundY, cosmetic.primaryMaterial());
            case FLOOR -> floorWrites(cosmetic, bounds, groundY);
            case CENTERPIECE -> centerpieceWrites(cosmetic, bounds, groundY);
            case GATE -> gateWrites(cosmetic, bounds, groundY);
        };
    }

    /** A one-block-wide trim along the plot's outer edge, at ground level - doesn't block movement or view. */
    @NotNull
    private static List<BlockWrite> borderWrites(
            @NotNull PlotGridAllocator.PlotBounds bounds, int groundY, @NotNull Material material) {
        List<BlockWrite> writes = new ArrayList<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            writes.add(new BlockWrite(x, groundY, bounds.minZ(), material));
            writes.add(new BlockWrite(x, groundY, bounds.maxZ(), material));
        }
        // The two Z edges above already cover both corners on every X row - skip them here
        // so the corner blocks aren't written twice (harmless if they were, just wasteful).
        for (int z = bounds.minZ() + 1; z <= bounds.maxZ() - 1; z++) {
            writes.add(new BlockWrite(bounds.minX(), groundY, z, material));
            writes.add(new BlockWrite(bounds.maxX(), groundY, z, material));
        }
        return writes;
    }

    /** Fills the plot's entire footprint at ground level - {@link PlotCosmetic#FLOOR_CHECKERED} alternates by {@code (x + z)} parity. */
    @NotNull
    private static List<BlockWrite> floorWrites(
            @NotNull PlotCosmetic cosmetic, @NotNull PlotGridAllocator.PlotBounds bounds, int groundY) {
        List<BlockWrite> writes = new ArrayList<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                boolean useSecondary = cosmetic == PlotCosmetic.FLOOR_CHECKERED && (x + z) % 2 == 0;
                Material material = useSecondary ? cosmetic.secondaryMaterial().orElseThrow() : cosmetic.primaryMaterial();
                writes.add(new BlockWrite(x, groundY, z, material));
            }
        }
        return writes;
    }

    /** A 3x3 base ({@link PlotCosmetic#secondaryMaterial()}) topped with a single centerpiece block, at the plot's center. */
    @NotNull
    private static List<BlockWrite> centerpieceWrites(
            @NotNull PlotCosmetic cosmetic, @NotNull PlotGridAllocator.PlotBounds bounds, int groundY) {
        int centerX = (bounds.minX() + bounds.maxX()) / 2;
        int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
        Material base = cosmetic.secondaryMaterial().orElseThrow();

        List<BlockWrite> writes = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                writes.add(new BlockWrite(centerX + dx, groundY, centerZ + dz, base));
            }
        }
        writes.add(new BlockWrite(centerX, groundY + 1, centerZ, cosmetic.primaryMaterial()));
        return writes;
    }

    /** A 2-block-wide opening at the midpoint of the plot's south edge, flanked by two 2-tall posts. */
    @NotNull
    private static List<BlockWrite> gateWrites(
            @NotNull PlotCosmetic cosmetic, @NotNull PlotGridAllocator.PlotBounds bounds, int groundY) {
        int centerX = (bounds.minX() + bounds.maxX()) / 2;
        int z = bounds.maxZ();
        Material postMaterial = cosmetic.primaryMaterial();
        Material gateMaterial = cosmetic.secondaryMaterial().orElse(postMaterial);

        List<BlockWrite> writes = new ArrayList<>();
        for (int dy = 0; dy <= 1; dy++) {
            writes.add(new BlockWrite(centerX - 2, groundY + dy, z, postMaterial));
            writes.add(new BlockWrite(centerX + 1, groundY + dy, z, postMaterial));
        }
        writes.add(new BlockWrite(centerX - 1, groundY, z, gateMaterial));
        writes.add(new BlockWrite(centerX, groundY, z, gateMaterial));
        return writes;
    }

    // ---- Bukkit-touching - GuildCommand only -----------------------------------------------

    /** Actually places every write in {@code writes}. Main-thread only. */
    public static void apply(@NotNull World world, @NotNull List<BlockWrite> writes) {
        for (BlockWrite write : writes) {
            world.getBlockAt(write.x(), write.y(), write.z()).setType(write.material());
        }
    }
}
