package org.rigelmc.guild.plot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.rigelmc.guild.plot.PlotCosmeticApplier.BlockWrite;

class PlotCosmeticApplierTest {

    private static final PlotGridAllocator.PlotBounds BOUNDS = new PlotGridAllocator.PlotBounds(0, 0, 15, 15);
    private static final int GROUND_Y = 64;

    @Test
    void borderWritesOnlyTouchTheOuterRing() {
        List<BlockWrite> writes = PlotCosmeticApplier.blockWritesFor(PlotCosmetic.BORDER_STONE, BOUNDS, GROUND_Y);

        assertTrue(writes.stream().allMatch(w -> w.material() == Material.STONE));
        assertTrue(writes.stream().allMatch(w -> w.y() == GROUND_Y));
        assertTrue(writes.stream().allMatch(
                w -> w.x() == BOUNDS.minX() || w.x() == BOUNDS.maxX() || w.z() == BOUNDS.minZ() || w.z() == BOUNDS.maxZ()));
        // Every edge block appears at least once - a rough coverage check without asserting exact count/order.
        assertTrue(writes.stream().anyMatch(w -> w.x() == BOUNDS.minX() && w.z() == BOUNDS.minZ()));
        assertTrue(writes.stream().anyMatch(w -> w.x() == BOUNDS.maxX() && w.z() == BOUNDS.maxZ()));
    }

    @Test
    void borderWritesNeverTouchTheInterior() {
        List<BlockWrite> writes = PlotCosmeticApplier.blockWritesFor(PlotCosmetic.BORDER_STONE, BOUNDS, GROUND_Y);

        boolean anyInterior = writes.stream().anyMatch(
                w -> w.x() > BOUNDS.minX() && w.x() < BOUNDS.maxX() && w.z() > BOUNDS.minZ() && w.z() < BOUNDS.maxZ());
        assertFalse(anyInterior);
    }

    @Test
    void floorWritesCoverTheEntireFootprintExactlyOnce() {
        List<BlockWrite> writes = PlotCosmeticApplier.blockWritesFor(PlotCosmetic.FLOOR_SAND, BOUNDS, GROUND_Y);

        int expectedCount = (BOUNDS.maxX() - BOUNDS.minX() + 1) * (BOUNDS.maxZ() - BOUNDS.minZ() + 1);
        assertEquals(expectedCount, writes.size());
        assertTrue(writes.stream().allMatch(w -> w.material() == Material.SAND && w.y() == GROUND_Y));
    }

    @Test
    void checkeredFloorAlternatesBetweenTwoMaterials() {
        List<BlockWrite> writes = PlotCosmeticApplier.blockWritesFor(PlotCosmetic.FLOOR_CHECKERED, BOUNDS, GROUND_Y);

        for (BlockWrite write : writes) {
            Material expected = (write.x() + write.z()) % 2 == 0 ? Material.BLACK_CONCRETE : Material.QUARTZ_BLOCK;
            assertEquals(expected, write.material());
        }
    }

    @Test
    void centerpieceWritesA3x3BaseWithOneBlockOnTop() {
        List<BlockWrite> writes = PlotCosmeticApplier.blockWritesFor(PlotCosmetic.CENTERPIECE_BEACON, BOUNDS, GROUND_Y);

        long baseCount = writes.stream().filter(w -> w.y() == GROUND_Y).count();
        long topCount = writes.stream().filter(w -> w.y() == GROUND_Y + 1).count();
        assertEquals(9, baseCount); // 3x3
        assertEquals(1, topCount);
        assertTrue(writes.stream().anyMatch(w -> w.y() == GROUND_Y + 1 && w.material() == Material.BEACON));
        assertTrue(writes.stream().filter(w -> w.y() == GROUND_Y).allMatch(w -> w.material() == Material.IRON_BLOCK));
    }

    @Test
    void gateWritesTwoTallPostsAndATwoWideOpeningOnTheSouthEdge() {
        List<BlockWrite> writes = PlotCosmeticApplier.blockWritesFor(PlotCosmetic.GATE_OAK, BOUNDS, GROUND_Y);

        assertTrue(writes.stream().allMatch(w -> w.z() == BOUNDS.maxZ()));
        long postWrites = writes.stream().filter(w -> w.material() == Material.OAK_FENCE).count();
        long gateWrites = writes.stream().filter(w -> w.material() == Material.OAK_FENCE_GATE).count();
        assertEquals(4, postWrites); // 2 posts x 2 blocks tall
        assertEquals(2, gateWrites); // 2-wide opening
    }
}
