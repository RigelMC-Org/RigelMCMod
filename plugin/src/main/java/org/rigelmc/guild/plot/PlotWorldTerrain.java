package org.rigelmc.guild.plot;

import org.jetbrains.annotations.NotNull;

/**
 * Pure grid classification for the plot world's visible terrain - given a world X/Z
 * coordinate and the configured grid geometry, decides whether that column falls inside a
 * plot's buildable footprint, on the one-block border wall ring immediately outside it, or
 * on the open road in between. No Bukkit dependency at all (see {@link PlotGridAllocator}'s
 * own javadoc for why this project keeps grid math pure and separately unit-testable) - the
 * only Bukkit-touching piece is {@link GuildPlotWorldPopulator}, which calls this once per
 * column while a chunk generates.
 *
 * <p>Tiles the same {@code plotSize + plotGap} cell {@link PlotGridAllocator} allocates
 * slots from, infinitely in both X and Z, entirely independent of {@code gridColumns} -
 * {@code gridColumns} only decides which cell a given slot index maps to (row-major
 * wrapping), never the physical repeating pattern itself, so the visible road network tiles
 * seamlessly under every slot without this class needing to know which slots are actually
 * claimed.</p>
 *
 * <p>The border ring sits entirely in the gap, one block outside a plot's footprint - not
 * carved out of the plot's own buildable area - so a plot's full {@code plot-size} stays
 * available to its owner, and the wall itself lives under the whole-plot-world boundary
 * lockdown (see {@code GuildPlotWorldService}'s class javadoc), not any individual plot's
 * membership, so not even that plot's own owner can grief their own border.
 *
 * <p><b>Each side of the gap gets its own border column</b>: a gap band of width {@code
 * plotGap} has exactly two plots touching it, one on each end (offset 0, immediately after
 * the plot before it; offset {@code plotGap - 1}, immediately before the plot after it) -
 * both of those columns are border, and anything strictly between them (only possible once
 * {@code plotGap >= 3}) is open road. Getting this wrong is an easy trap: a naive "one
 * border column per gap, right after the plot" only walls off two of a plot's four sides,
 * leaving the other two touching bare road directly - caught by {@code
 * PlotWorldTerrainTest} before it ever shipped. With {@code plotGap == 0} every column is
 * always in-plot (no room for a border or road at all) - plots simply touch directly, the
 * same silent degrade {@link PlotGridAllocator} already has for that configuration. {@code
 * plotGap == 1} or {@code 2} leaves both neighboring plots' single/two border column(s)
 * touching directly, with no open road between them.</p>
 *
 * <p><b>Intersection squares (where a 4-plot corner meets) are fully solid</b> -
 * user-reported: applying the straight-edge rule per-axis at a corner used to leave
 * {@code BORDER} only at the single column closest to each of the (up to) 4 surrounding
 * plots, with open road filling the rest of the square between them - four disconnected,
 * never-touching wall stubs around an open diamond of pavement. Once neither axis is inside
 * a plot's own footprint (both are somewhere in the gap simultaneously - i.e. this column is
 * inside the {@code plotGap x plotGap} intersection square, not on a straight edge), the
 * whole square is {@code BORDER}: every corner reads as a clean, fully-enclosed wall with no
 * gap. Straight roads between two adjacent plots are untouched by this - they still run the
 * full open width right up to the intersection, then dead-end into this solid block rather
 * than passing through it (plots are reached via {@code /guild plot tp}, not on-foot travel
 * across the whole grid, so a non-passable intersection costs nothing functionally).</p>
 */
public final class PlotWorldTerrain {

    private PlotWorldTerrain() {
    }

    /** What a single world column should look like at ground level. */
    public enum CellType { PLOT, BORDER, ROAD }

    /** Per-axis classification before the two axes are combined - see {@link #classify}. */
    private enum AxisZone { PLOT, EDGE, MIDDLE }

    /**
     * @param worldX absolute world X (any sign - uses floor-mod tiling, not Java's
     *     sign-preserving {@code %}, since the boundary lockdown's own bounds extend into
     *     negative coordinates - see {@code GuildPlotWorldService#computeBoundaryBounds})
     * @param worldZ absolute world Z, same caveat
     * @param plotSize the side length of one plot's buildable footprint, in blocks
     * @param plotGap the empty buffer between adjacent plots, in blocks
     * @return which kind of column this is
     */
    @NotNull
    public static CellType classify(int worldX, int worldZ, int plotSize, int plotGap) {
        if (plotSize <= 0) {
            throw new IllegalArgumentException("plotSize must be positive: " + plotSize);
        }
        if (plotGap < 0) {
            throw new IllegalArgumentException("plotGap must not be negative: " + plotGap);
        }

        int cell = plotSize + plotGap;
        AxisZone zoneX = axisZone(Math.floorMod(worldX, cell), plotSize, plotGap);
        AxisZone zoneZ = axisZone(Math.floorMod(worldZ, cell), plotSize, plotGap);

        boolean xIsPlot = zoneX == AxisZone.PLOT;
        boolean zIsPlot = zoneZ == AxisZone.PLOT;
        if (xIsPlot && zIsPlot) {
            return CellType.PLOT;
        }
        if (xIsPlot || zIsPlot) {
            // A straight edge, not a corner intersection - unchanged from before this fix:
            // the gap axis's own EDGE/MIDDLE zone alone decides border vs road.
            AxisZone gapZone = xIsPlot ? zoneZ : zoneX;
            return gapZone == AxisZone.MIDDLE ? CellType.ROAD : CellType.BORDER;
        }
        // Neither axis is PLOT - this column is inside an intersection square, where up to 4
        // different plots' corners approach diagonally. See class javadoc: the whole square
        // is BORDER, fully sealing every corner - previously only the single column closest
        // to each plot was BORDER here, leaving the rest of the square as open road and four
        // disconnected wall stubs that never touched each other.
        return CellType.BORDER;
    }

    /** @return which zone {@code local} (already floor-mod'd into {@code [0, plotSize + plotGap)}) falls into on one axis. */
    @NotNull
    private static AxisZone axisZone(int local, int plotSize, int plotGap) {
        if (local < plotSize) {
            return AxisZone.PLOT;
        }
        int gapOffset = local - plotSize;
        boolean touchesPlotBefore = gapOffset == 0;
        boolean touchesPlotAfter = gapOffset == plotGap - 1;
        return (touchesPlotBefore || touchesPlotAfter) ? AxisZone.EDGE : AxisZone.MIDDLE;
    }
}
