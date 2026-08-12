package org.rigelmc.guild.plot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlotGridAllocatorTest {

    @Test
    void slotZeroSitsAtTheOrigin() {
        PlotGridAllocator.PlotBounds bounds = PlotGridAllocator.boundsForSlot(0, 16, 4, 10);
        assertEquals(0, bounds.minX());
        assertEquals(0, bounds.minZ());
        assertEquals(15, bounds.maxX());
        assertEquals(15, bounds.maxZ());
    }

    @Test
    void slotsPackLeftToRightWithinARow() {
        PlotGridAllocator.PlotBounds first = PlotGridAllocator.boundsForSlot(0, 16, 4, 10);
        PlotGridAllocator.PlotBounds second = PlotGridAllocator.boundsForSlot(1, 16, 4, 10);

        assertEquals(20, second.minX()); // plotSize (16) + plotGap (4)
        assertEquals(first.minZ(), second.minZ()); // same row
    }

    @Test
    void slotsWrapToTheNextRowAtGridColumns() {
        PlotGridAllocator.PlotBounds lastInRow = PlotGridAllocator.boundsForSlot(9, 16, 4, 10);
        PlotGridAllocator.PlotBounds firstOfNextRow = PlotGridAllocator.boundsForSlot(10, 16, 4, 10);

        assertEquals(0, firstOfNextRow.minX()); // wrapped back to column 0
        assertEquals(20, firstOfNextRow.minZ()); // one row down
        assertEquals(180, lastInRow.minX()); // column 9: 9 * (16 + 4)
    }

    @Test
    void adjacentPlotsNeverOverlapGivenAPositiveGap() {
        PlotGridAllocator.PlotBounds first = PlotGridAllocator.boundsForSlot(0, 16, 4, 10);
        PlotGridAllocator.PlotBounds second = PlotGridAllocator.boundsForSlot(1, 16, 4, 10);

        assertTrue(first.maxX() < second.minX());
    }

    @Test
    void rejectsNonPositivePlotSize() {
        assertThrows(IllegalArgumentException.class, () -> PlotGridAllocator.boundsForSlot(0, 0, 4, 10));
    }

    @Test
    void rejectsNonPositiveGridColumns() {
        assertThrows(IllegalArgumentException.class, () -> PlotGridAllocator.boundsForSlot(0, 16, 4, 0));
    }

    @Test
    void rejectsANegativeSlotIndex() {
        assertThrows(IllegalArgumentException.class, () -> PlotGridAllocator.boundsForSlot(-1, 16, 4, 10));
    }
}
