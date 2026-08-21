package org.rigelmc.guild.plot;

import java.util.logging.Logger;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.core.RigelConfig;

/**
 * The four block types {@link GuildPlotWorldPopulator} builds the plot world's road network
 * out of, resolved once from {@code guild.plotworld.materials.*}.
 *
 * <p>User-requested after a live look at the generated result: these were hardcoded
 * constants, so retheming the plot world meant a code change. An unrecognised material name
 * falls back to that slot's default and logs a warning rather than failing world creation -
 * a typo in one cosmetic config value should never stop the plot world from generating.</p>
 *
 * <p>{@link #intersection()} exists separately from {@link #borderWall()} because a wall
 * block is a thin connecting fence post, not a full cube: capping a whole {@code plotGap x
 * plotGap} crossing with it rendered as a bumpy lattice of disconnected posts instead of a
 * clean solid crossing. Straight one-column-wide borders still use the wall block, which is
 * exactly what it is good for.</p>
 */
public record PlotWorldMaterials(
        @NotNull Material road,
        @NotNull Material borderFoundation,
        @NotNull Material borderWall,
        @NotNull Material intersection) {

    private static final Material DEFAULT_ROAD = Material.STONE;
    private static final Material DEFAULT_BORDER_FOUNDATION = Material.STONE_BRICKS;
    private static final Material DEFAULT_BORDER_WALL = Material.COBBLESTONE_WALL;
    private static final Material DEFAULT_INTERSECTION = Material.STONE_BRICKS;

    @NotNull
    public static PlotWorldMaterials fromConfig(@NotNull RigelConfig config, @NotNull Logger logger) {
        return new PlotWorldMaterials(
                resolve(config.guildPlotRoadMaterial(), DEFAULT_ROAD, "road", logger),
                resolve(config.guildPlotBorderFoundationMaterial(), DEFAULT_BORDER_FOUNDATION,
                        "border-foundation", logger),
                resolve(config.guildPlotBorderWallMaterial(), DEFAULT_BORDER_WALL, "border-wall", logger),
                resolve(config.guildPlotIntersectionMaterial(), DEFAULT_INTERSECTION, "intersection", logger));
    }

    @NotNull
    private static Material resolve(
            @NotNull String configured, @NotNull Material fallback, @NotNull String key, @NotNull Logger logger) {
        Material material = Material.matchMaterial(configured);
        if (material == null || !material.isBlock()) {
            logger.warning("guild.plotworld.materials." + key + " is '" + configured
                    + "', which is not a known block - falling back to " + fallback + ".");
            return fallback;
        }
        return material;
    }
}
