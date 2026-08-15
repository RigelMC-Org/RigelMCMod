package org.rigelmc.guild.plot;

import java.util.Random;
import org.bukkit.Material;
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

    /** Road surface, filling the gap between border rings. */
    private static final Material ROAD_MATERIAL = Material.STONE;
    /** The border's solid foundation, filling every block from bedrock up through ground level. */
    private static final Material BORDER_FOUNDATION_MATERIAL = Material.STONE_BRICKS;
    /** One block above the foundation - the actual visible wall separating plots from the road. */
    private static final Material BORDER_WALL_MATERIAL = Material.COBBLESTONE_WALL;

    private final int plotSize;
    private final int plotGap;

    public GuildPlotWorldPopulator(int plotSize, int plotGap) {
        this.plotSize = plotSize;
        this.plotGap = plotGap;
    }

    @Override
    public void populate(
            @NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
            @NotNull LimitedRegion limitedRegion) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int worldX = baseX + dx;
                int worldZ = baseZ + dz;
                // The real top of whatever the generator already produced for this column -
                // see class javadoc for why this is never trusted from config instead.
                int groundY = limitedRegion.getHighestBlockYAt(worldX, worldZ);
                if (groundY <= worldInfo.getMinHeight() || groundY + 1 > worldInfo.getMaxHeight()) {
                    continue; // no solid ground found here (or no room for a wall above it) - leave untouched
                }
                PlotWorldTerrain.CellType type = PlotWorldTerrain.classify(worldX, worldZ, plotSize, plotGap);
                switch (type) {
                    case ROAD -> limitedRegion.setType(worldX, groundY, worldZ, ROAD_MATERIAL);
                    case BORDER -> {
                        // Solid all the way down to bedrock (user-requested), not just a
                        // one-block footing under the wall - see class javadoc.
                        for (int y = worldInfo.getMinHeight(); y <= groundY; y++) {
                            limitedRegion.setType(worldX, y, worldZ, BORDER_FOUNDATION_MATERIAL);
                        }
                        limitedRegion.setType(worldX, groundY + 1, worldZ, BORDER_WALL_MATERIAL);
                    }
                    case PLOT -> {
                        // Leave the generator's own ground (grass, by default) untouched.
                    }
                }
            }
        }
    }
}
