package org.rigelmc.guild.plot;

import org.jetbrains.annotations.NotNull;

/**
 * Pure row-major grid math - a plot slot index (0, 1, 2, ...) maps deterministically to an
 * X/Z footprint, no I/O and no Bukkit dependency at all, so it's fully unit-testable (see
 * {@code PlotGridAllocatorTest}) independently of everything else in this package. Y bounds
 * aren't computed here - see {@link GuildPlotWorldService}'s javadoc for why those come from
 * a fixed ground-relative band instead of a live {@code World}'s real height.
 *
 * <p>Slots pack left-to-right, then wrap to the next row - slot 0 at grid cell (0, 0), slot
 * {@code gridColumns} at (0, 1), and so on. Cells are {@code plotSize + plotGap} apart on
 * both axes, so every plot has a fixed-width empty buffer to its neighbors on every side.</p>
 */
public final class PlotGridAllocator {

    private PlotGridAllocator() {
    }

    /** Inclusive X/Z bounds for one plot slot - Y is handled separately, see this class's javadoc. */
    public record PlotBounds(int minX, int minZ, int maxX, int maxZ) {
    }

    /**
     * @param slotIndex 0-based, row-major
     * @param plotSize the side length of one plot's buildable footprint, in blocks
     * @param plotGap the empty buffer between adjacent plots, in blocks
     * @param gridColumns how many plots per row before wrapping to the next row
     * @return the inclusive X/Z bounds for this slot
     */
    @NotNull
    public static PlotBounds boundsForSlot(int slotIndex, int plotSize, int plotGap, int gridColumns) {
        if (slotIndex < 0) {
            throw new IllegalArgumentException("slotIndex must not be negative: " + slotIndex);
        }
        if (plotSize <= 0) {
            throw new IllegalArgumentException("plotSize must be positive: " + plotSize);
        }
        if (plotGap < 0) {
            throw new IllegalArgumentException("plotGap must not be negative: " + plotGap);
        }
        if (gridColumns <= 0) {
            throw new IllegalArgumentException("gridColumns must be positive: " + gridColumns);
        }

        int cell = plotSize + plotGap;
        int column = slotIndex % gridColumns;
        int row = slotIndex / gridColumns;
        int minX = column * cell;
        int minZ = row * cell;
        return new PlotBounds(minX, minZ, minX + plotSize - 1, minZ + plotSize - 1);
    }
}
