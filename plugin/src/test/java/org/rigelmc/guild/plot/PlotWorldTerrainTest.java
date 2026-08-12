package org.rigelmc.guild.plot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PlotWorldTerrainTest {

    @Test
    void theCenterOfAPlotIsPlot() {
        assertEquals(PlotWorldTerrain.CellType.PLOT, PlotWorldTerrain.classify(8, 8, 16, 4));
    }

    @Test
    void theFarCornerOfAPlotsOwnFootprintIsStillPlotNotBorder() {
        // The border ring lives one block outside the footprint (see class javadoc) - a
        // plot's own edge column (index plotSize - 1) stays fully buildable.
        assertEquals(PlotWorldTerrain.CellType.PLOT, PlotWorldTerrain.classify(15, 8, 16, 4));
        assertEquals(PlotWorldTerrain.CellType.PLOT, PlotWorldTerrain.classify(8, 15, 16, 4));
    }

    @Test
    void oneBlockPastAPlotsEdgeIsBorder() {
        assertEquals(PlotWorldTerrain.CellType.BORDER, PlotWorldTerrain.classify(16, 8, 16, 4));
        assertEquals(PlotWorldTerrain.CellType.BORDER, PlotWorldTerrain.classify(8, 16, 16, 4));
    }

    @Test
    void theDiagonalCornerOfTheBorderRingIsBorder() {
        assertEquals(PlotWorldTerrain.CellType.BORDER, PlotWorldTerrain.classify(16, 16, 16, 4));
    }

    @Test
    void theMiddleOfTheGapIsRoad() {
        // plotSize=16, plotGap=4: local gap columns are 16,17,18,19 - 16 is border, 17-18 is
        // open road, 19 is the border ring of the *next* cell over (local 0 of the next tile).
        assertEquals(PlotWorldTerrain.CellType.ROAD, PlotWorldTerrain.classify(17, 8, 16, 4));
        assertEquals(PlotWorldTerrain.CellType.ROAD, PlotWorldTerrain.classify(18, 8, 16, 4));
    }

    @Test
    void theRoadNeverAppearsDiagonallyBetweenFourPlotCorners() {
        // At a 4-plot intersection (both axes deep in the gap simultaneously, not on either
        // border ring) it's still open road, not border - only the ring exactly one block
        // out from a footprint is border.
        assertEquals(PlotWorldTerrain.CellType.ROAD, PlotWorldTerrain.classify(17, 17, 16, 4));
    }

    @Test
    void tilesSeamlesslyIntoTheNextCell() {
        // worldX=20 is local 0 of the next cell over (cell = 16 + 4 = 20) - back to PLOT.
        assertEquals(PlotWorldTerrain.CellType.PLOT, PlotWorldTerrain.classify(20, 8, 16, 4));
    }

    @Test
    void negativeCoordinatesTileCorrectlyViaFloorModNotJavasSignPreservingRemainder() {
        // The boundary lockdown's own bounds extend into negative X/Z (see
        // GuildPlotWorldService#computeBoundaryBounds) - classify must still tile correctly
        // there, not just for positive coordinates. worldX=-1/-16 sit one block outside the
        // origin plot's low edge (both axes at the border touching that plot's "before"
        // side) - BORDER, not the buggy old behavior of ROAD or the naive PLOT guess.
        assertEquals(PlotWorldTerrain.CellType.BORDER, PlotWorldTerrain.classify(-1, -1, 16, 4));
        // worldX=-15 lands inside the *previous* cell's own plot (cell index -1, local
        // coordinate 5) - genuinely PLOT, one full cell further out.
        assertEquals(PlotWorldTerrain.CellType.PLOT, PlotWorldTerrain.classify(-15, -15, 16, 4));
    }

    @Test
    void everyGapColumnHasABorderOnBothSidesNotJustOneSideOfTheCell() {
        // Regression coverage for the original bug: a naive "border at gap-offset 0 only"
        // walls off just two of a plot's four sides, leaving the other two touching bare
        // road directly. worldX=-1 is immediately below the origin plot (0..15) and must be
        // BORDER, exactly like worldX=16 (immediately above it) already is.
        assertEquals(PlotWorldTerrain.CellType.BORDER, PlotWorldTerrain.classify(16, 8, 16, 4));
        assertEquals(PlotWorldTerrain.CellType.BORDER, PlotWorldTerrain.classify(-1, 8, 16, 4));
    }

    @Test
    void zeroGapMeansEveryColumnIsPlotWithNoRoomForBorderOrRoad() {
        assertEquals(PlotWorldTerrain.CellType.PLOT, PlotWorldTerrain.classify(0, 0, 16, 0));
        assertEquals(PlotWorldTerrain.CellType.PLOT, PlotWorldTerrain.classify(15, 15, 16, 0));
    }

    @Test
    void oneBlockGapLeavesNoRoomForOpenRoadOnlyTouchingBorders() {
        assertEquals(PlotWorldTerrain.CellType.BORDER, PlotWorldTerrain.classify(16, 8, 16, 1));
        // worldX=17 is already local 0 of the next cell (cell = 16 + 1 = 17).
        assertEquals(PlotWorldTerrain.CellType.PLOT, PlotWorldTerrain.classify(17, 8, 16, 1));
    }

    @Test
    void rejectsNonPositivePlotSize() {
        assertThrows(IllegalArgumentException.class, () -> PlotWorldTerrain.classify(0, 0, 0, 4));
    }

    @Test
    void rejectsANegativePlotGap() {
        assertThrows(IllegalArgumentException.class, () -> PlotWorldTerrain.classify(0, 0, 16, -1));
    }
}
