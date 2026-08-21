package org.rigelmc.guild.plot;

import java.util.Random;
import org.bukkit.Material;
import org.bukkit.RegionAccessor;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

/**
 * Paints the plot world's road network and per-plot border walls into every chunk as it
 * generates - see {@link PlotWorldTerrain} for the pure classification this is a thin
 * Bukkit wrapper around. Registered onto the plot world's {@code World#getPopulators()}
 * list every time {@code GuildPlotWorldService#ensureWorldExists} attaches to it - both a
 * brand-new world and a fresh server boot re-attaching to an existing one on disk get a
 * fresh, empty populators list from Bukkit each JVM run (populators are never persisted to
 * disk), so this is re-added on every enable rather than once ever. The grid settings baked
 * into a given instance never change afterward, matching this project's existing "changing
 * guild.plotworld.* doesn't retroactively resize what's already generated" behavior - a
 * {@code /wipeguildplots confirm} regenerates the world, and therefore every chunk, under
 * whatever settings are current at that point.
 *
 * <p>Only ever applies to chunks generated <i>after</i> this populator was attached -
 * Bukkit never re-populates an already-generated chunk. A plot world that existed before
 * this feature shipped keeps its old, road-less terrain until {@code /wipeguildplots
 * confirm} deletes and regenerates it from scratch.</p>
 *
 * <p><b>That "after it was attached" caveat has one unavoidable blind spot</b>, and it was
 * a real, user-reported bug: Bukkit generates a world's spawn-chunk region during {@code
 * WorldCreator#createWorld()} itself, so those chunks always predate this populator no
 * matter how early it's attached. {@code
 * GuildPlotWorldService#repairPreGeneratedChunks} runs {@link #paintChunk} over exactly
 * those chunks right after attaching, as a self-healing second pass - see its javadoc for
 * the full story.</p>
 *
 * <p><b>Deliberately reads each column's real generated ground height at population time
 * ({@link LimitedRegion#getHighestBlockYAt}) rather than trusting {@code
 * guild.plotworld.ground-y} from config</b> - a real, user-reported bug in the first
 * version of this class: when the optional CleanroomGenerator plugin isn't installed (the
 * common case - it's a niche, Eaglercraft-specific soft dependency, see {@code
 * world.CleanroomGeneratorBridge}'s javadoc), plot-world creation silently falls back to
 * bare vanilla {@code WorldType.FLAT} with no matching {@code generatorSettings}, which
 * generates at Bukkit's own default flat height - almost never the same Y as whatever
 * {@code ground-y} happens to be configured to. The first version of this populator wrote
 * roads/borders at the configured {@code ground-y} regardless, producing an entire road
 * network floating in mid-air over the real (much lower, in the reported case) generated
 * ground. Querying the actual generated height per column instead is correct regardless of
 * which generator produced the world or whether it matches config at all - the border/road
 * always lands exactly on the real surface.</p>
 *
 * <p><b>Border columns are solid from the surface all the way down to bedrock</b>
 * (user-requested) - not just a thin footing under the visible wall block. A player
 * tunneling underground can't pop out the bottom of a border by digging under it; every
 * border column is one unbroken foundation from {@link WorldInfo#getMinHeight()} up
 * through ground level, topped with the single visible wall block.</p>
 */
public final class GuildPlotWorldPopulator extends BlockPopulator {

    private final int plotSize;
    private final int plotGap;
    private final PlotWorldMaterials materials;

    public GuildPlotWorldPopulator(int plotSize, int plotGap, @NotNull PlotWorldMaterials materials) {
        this.plotSize = plotSize;
        this.plotGap = plotGap;
        this.materials = materials;
    }

    @Override
    public void populate(
            @NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
            @NotNull LimitedRegion limitedRegion) {
        paintChunk(worldInfo, limitedRegion, chunkX, chunkZ, plotSize, plotGap, materials);
    }

    /**
     * Paints one chunk's worth of road/border columns.
     *
     * <p>Split out of {@link #populate} and written against {@link RegionAccessor} - the
     * common supertype of both {@link LimitedRegion} (what a populator gets) and {@link
     * org.bukkit.World} (what a plain live world is) - so {@code
     * GuildPlotWorldService#ensureWorldExists} can run the <i>exact same</i> logic, not a
     * reimplementation of it, over the spawn chunks Bukkit pre-generates during {@code
     * WorldCreator#createWorld()}. Those chunks already exist by the time a populator can
     * possibly be attached, and Bukkit never re-populates an existing chunk (see this
     * class's own javadoc), so without that second pass they keep bare, road-less terrain
     * forever - a real, user-reported bug that showed up as broken plot corners around world
     * origin while every other corner in the world was correct.</p>
     *
     * <p><b>Idempotent</b>, deliberately: running it twice over the same chunk changes
     * nothing the second time. That is a load-bearing property, not a nicety - {@code
     * GuildPlotWorldService#repairPreGeneratedChunks} re-runs this over already-generated
     * chunks on every enable to self-heal a world that was created before the second pass
     * existed, which is only safe because a painted column is detected and skipped rather
     * than stacked on top of.</p>
     */
    public static void paintChunk(
            @NotNull WorldInfo worldInfo, @NotNull RegionAccessor region, int chunkX, int chunkZ,
            int plotSize, int plotGap, @NotNull PlotWorldMaterials materials) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int worldX = baseX + dx;
                int worldZ = baseZ + dz;
                // The real top of whatever the generator already produced for this column -
                // see class javadoc for why this is never trusted from config instead.
                int groundY = region.getHighestBlockYAt(worldX, worldZ);
                if (groundY <= worldInfo.getMinHeight() || groundY + 1 > worldInfo.getMaxHeight()) {
                    continue; // no solid ground found here (or no room for a wall above it) - leave untouched
                }
                PlotWorldTerrain.CellType type = PlotWorldTerrain.classify(worldX, worldZ, plotSize, plotGap);
                switch (type) {
                    case ROAD -> {
                        if (region.getType(worldX, groundY, worldZ) != materials.road()) {
                            region.setType(worldX, groundY, worldZ, materials.road());
                        }
                    }
                    // BORDER and INTERSECTION build identically apart from the block capping
                    // them: a straight edge gets the wall block (a thin fence post, which is
                    // what a one-column-wide border should look like), while an intersection
                    // square gets a full solid cube. User-reported: capping the whole
                    // plotGap x plotGap square with wall blocks rendered as a bumpy lattice
                    // of disconnected posts rather than a clean crossing.
                    case BORDER -> paintPillar(
                            worldInfo, region, worldX, worldZ, groundY, materials, materials.borderWall());
                    case INTERSECTION -> paintPillar(
                            worldInfo, region, worldX, worldZ, groundY, materials, materials.intersection());
                    case PLOT -> {
                        // Leave the generator's own ground (grass, by default) untouched.
                    }
                }
            }
        }
    }

    /**
     * Foundation from bedrock up through ground level, capped with {@code capMaterial} one
     * block above it.
     *
     * <p>The already-capped check is what makes {@link #paintChunk} safely re-runnable over
     * an existing world (see its javadoc): once a column is painted, its highest block IS the
     * cap, so the {@code groundY} passed in is really the cap's own Y. Without this check a
     * second pass would lay a fresh foundation up to there and place another cap one block
     * higher, growing every border and crossing upward on every single run.</p>
     */
    private static void paintPillar(
            @NotNull WorldInfo worldInfo, @NotNull RegionAccessor region, int worldX, int worldZ,
            int groundY, @NotNull PlotWorldMaterials materials, @NotNull Material capMaterial) {
        if (region.getType(worldX, groundY, worldZ) == capMaterial) {
            return;
        }
        // Solid all the way down to bedrock (user-requested), not just a one-block footing
        // under the cap - see class javadoc.
        for (int y = worldInfo.getMinHeight(); y <= groundY; y++) {
            region.setType(worldX, y, worldZ, materials.borderFoundation());
        }
        region.setType(worldX, groundY + 1, worldZ, capMaterial);
    }
}
