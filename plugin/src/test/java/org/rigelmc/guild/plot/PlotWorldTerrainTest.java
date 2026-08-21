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
    void theDiagonalCornerOfTheBorderRingIsAnIntersection() {
        // Both axes are in the gap here, so this is inside the intersection square rather
        // than on a straight edge - INTERSECTION, not BORDER, so it gets capped with a solid
        // block instead of a thin wall post. See PlotWorldTerrain.CellType.INTERSECTION.
        assertEquals(PlotWorldTerrain.CellType.INTERSECTION, PlotWorldTerrain.classify(16, 16, 16, 4));
    }

    @Test
    void theMiddleOfTheGapIsRoad() {
        // plotSize=16, plotGap=4: local gap columns are 16,17,18,19 - 16 is border, 17-18 is
        // open road, 19 is the border ring of the *next* cell over (local 0 of the next tile).
        // Deliberately unaffected by the intersection-square fix below - both of these calls
        // have Z fixed inside a plot's own footprint (8 < 16), so they're on a straight edge,
        // not inside a corner intersection square.
        assertEquals(PlotWorldTerrain.CellType.ROAD, PlotWorldTerrain.classify(17, 8, 16, 4));
        assertEquals(PlotWorldTerrain.CellType.ROAD, PlotWorldTerrain.classify(18, 8, 16, 4));
    }

    @Test
    void theIntersectionSquareIsFullySealedNotOpenRoad() {
        // User-reported bug, fixed: a 4-plot intersection (both axes deep in the gap
        // simultaneously, not just on either plot's own straight-edge border ring) used to
        // stay open road - only the single column closest to each of the (up to) 4
        // surrounding plots was walled, leaving 4 disconnected wall stubs around an open
        // diamond of pavement. The whole square is INTERSECTION now - every corner reads as
        // one clean, fully-enclosed solid crossing.
        assertEquals(PlotWorldTerrain.CellType.INTERSECTION, PlotWorldTerrain.classify(17, 17, 16, 4));
    }

    @Test
    void everyColumnOfTheIntersectionSquareIsSealedNotJustTheFourCorners() {
        // Regression guard for the exact reported bug: sweep the entire plotGap x plotGap
        // intersection square (plotSize=16, plotGap=4 -> local gap columns 16..19 on both
        // axes) and assert every one of the 16 cells is sealed, not just the 4 corner points
        // closest to a plot. This is what actually would have caught the original bug (only
        // 4 of these 16 cells were walled, the other 12 were open ROAD).
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                int worldX = 16 + dx;
                int worldZ = 16 + dz;
                assertEquals(
                        PlotWorldTerrain.CellType.INTERSECTION,
                        PlotWorldTerrain.classify(worldX, worldZ, 16, 4),
                        "expected INTERSECTION at square column (" + worldX + ", " + worldZ + ")");
            }
        }
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
        // origin plot's low edge - both axes land in the gap at once, so this is inside the
        // intersection square, not the buggy old behavior of ROAD or the naive PLOT guess.
        assertEquals(PlotWorldTerrain.CellType.INTERSECTION, PlotWorldTerrain.classify(-1, -1, 16, 4));
        // worldX=-15 lands inside the *previous* cell's own plot (cell index -1, local
        // coordinate 5) - genuinely PLOT, one full cell further out.
        assertEquals(PlotWorldTerrain.CellType.PLOT, PlotWorldTerrain.classify(-15, -15, 16, 4));
    }

    @Test
    void everyGapColumnHasABorderOnBothSidesNotJustOneSideOfTheCell() {
        // Regression coverage for the original bug: a naive "border at gap-offset 0 only"
        // walls off just two of a plot's four sides, leaving the other two touching bare
        // road directly. worldX=-1 is immediately below the origin plot (0..15) and must be
        // BORDER, exactly like worldX=16 (immediately above it) already is. Deliberately
        // unaffected by the intersection-square fix above - Z is fixed inside the plot's own
        // footprint (8 < 16) in both calls, so these are straight-edge columns, not inside a
        // corner intersection square.
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
