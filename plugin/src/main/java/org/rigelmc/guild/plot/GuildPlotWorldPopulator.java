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
 */
public final class GuildPlotWorldPopulator extends BlockPopulator {

    /** Road surface, filling the gap between border rings. */
    private static final Material ROAD_MATERIAL = Material.STONE;
    /** Border footing, flush with the ground - the block directly under the wall. */
    private static final Material BORDER_BASE_MATERIAL = Material.STONE_BRICKS;
    /** One block above the footing - the actual visible wall separating plots from the road. */
    private static final Material BORDER_WALL_MATERIAL = Material.COBBLESTONE_WALL;

    private final int plotSize;
    private final int plotGap;
    private final int groundY;

    public GuildPlotWorldPopulator(int plotSize, int plotGap, int groundY) {
        this.plotSize = plotSize;
        this.plotGap = plotGap;
        this.groundY = groundY;
    }

    @Override
    public void populate(
            @NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
            @NotNull LimitedRegion limitedRegion) {
        if (groundY < worldInfo.getMinHeight() || groundY + 1 > worldInfo.getMaxHeight()) {
            return; // ground-y sits outside this world's actual height range - nothing safe to paint
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int worldX = baseX + dx;
                int worldZ = baseZ + dz;
                PlotWorldTerrain.CellType type = PlotWorldTerrain.classify(worldX, worldZ, plotSize, plotGap);
                switch (type) {
                    case ROAD -> limitedRegion.setType(worldX, groundY, worldZ, ROAD_MATERIAL);
                    case BORDER -> {
                        limitedRegion.setType(worldX, groundY, worldZ, BORDER_BASE_MATERIAL);
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
