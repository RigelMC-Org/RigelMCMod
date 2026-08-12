package org.rigelmc.guild.plot;

import java.util.Optional;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The hardcoded plot-cosmetic catalog - a fixed, small set (matching {@link
 * org.rigelmc.protect.area.AreaFlag}'s own "small, hardcoded, not an open-ended registry"
 * precedent), not config-driven. Prices are tunable placeholders, easy to retune later
 * without a migration since they're compiled-in, not stored - only the fact that a
 * (guild, cosmetic) pair was ever purchased is persisted (see {@link PlotCosmeticDao}), so
 * changing a price here never retroactively affects an already-owned cosmetic.
 *
 * <p>Bought from the buyer's personal balance, not a shared guild treasury (no guild-bank
 * concept in scope) - see {@link PlotCosmeticService#buy}. Placed via plain {@link
 * org.bukkit.block.Block#setType}, never WorldEdit - see {@link PlotCosmeticApplier}'s
 * javadoc for why that's both simpler and sufficient here.</p>
 */
public enum PlotCosmetic {

    BORDER_STONE("border-stone", "Stone Border", Category.BORDER, 250, Material.STONE, null),
    BORDER_QUARTZ("border-quartz", "Quartz Border", Category.BORDER, 400, Material.QUARTZ_BLOCK, null),
    BORDER_PRISMARINE("border-prismarine", "Prismarine Border", Category.BORDER, 600, Material.PRISMARINE, null),

    FLOOR_SAND("floor-sand", "Sand Floor", Category.FLOOR, 150, Material.SAND, null),
    FLOOR_END_STONE("floor-end-stone", "End Stone Floor", Category.FLOOR, 300, Material.END_STONE, null),
    FLOOR_CHECKERED("floor-checkered", "Checkered Floor", Category.FLOOR, 500, Material.QUARTZ_BLOCK, Material.BLACK_CONCRETE),
    /** Free - resets a plot's floor back to the plot world's own default top layer (grass), not a purchase in any real sense. */
    FLOOR_DEFAULT("floor-default", "Default Floor (reset)", Category.FLOOR, 0, Material.GRASS_BLOCK, null),

    CENTERPIECE_BEACON("centerpiece-beacon", "Beacon Pyramid", Category.CENTERPIECE, 1000, Material.BEACON, Material.IRON_BLOCK),

    GATE_OAK("gate-oak", "Oak Gate", Category.GATE, 200, Material.OAK_FENCE, Material.OAK_FENCE_GATE),
    GATE_IRON("gate-iron", "Iron Gate", Category.GATE, 450, Material.IRON_BARS, Material.IRON_BARS);

    /** What part of the plot a cosmetic decorates - purely descriptive, doesn't gate anything on its own. */
    public enum Category { BORDER, FLOOR, CENTERPIECE, GATE }

    private final String key;
    private final String displayName;
    private final Category category;
    private final long price;
    private final Material primaryMaterial;
    private final Material secondaryMaterial;

    PlotCosmetic(
            String key, String displayName, Category category, long price, Material primaryMaterial,
            @Nullable Material secondaryMaterial) {
        this.key = key;
        this.displayName = displayName;
        this.category = category;
        this.price = price;
        this.primaryMaterial = primaryMaterial;
        this.secondaryMaterial = secondaryMaterial;
    }

    /** @return the stable, lowercase, command-facing key (e.g. {@code "border-stone"}) - also the DB {@code cosmetic_key}. */
    @NotNull
    public String key() {
        return key;
    }

    @NotNull
    public String displayName() {
        return displayName;
    }

    @NotNull
    public Category category() {
        return category;
    }

    /** @return the Coins price - {@code 0} for {@link #FLOOR_DEFAULT}, meaning it's free (never actually charged, see {@link PlotCosmeticService#buy}). */
    public long price() {
        return price;
    }

    /** @return the block a single-material cosmetic (border/floor/centerpiece cap) is made of, or the "primary" of a two-material one. */
    @NotNull
    public Material primaryMaterial() {
        return primaryMaterial;
    }

    /**
     * @return the second material a two-tone cosmetic needs - {@link #FLOOR_CHECKERED}'s
     *     alternate tile, {@link #CENTERPIECE_BEACON}'s pyramid base, or a gate's opening
     *     material where it differs from its posts. Empty for the plain single-material
     *     cosmetics.
     */
    @NotNull
    public Optional<Material> secondaryMaterial() {
        return Optional.ofNullable(secondaryMaterial);
    }

    /** @return the cosmetic whose {@link #key()} matches {@code key} (case-insensitive), or empty if none does. */
    @NotNull
    public static Optional<PlotCosmetic> byKey(@NotNull String key) {
        for (PlotCosmetic cosmetic : values()) {
            if (cosmetic.key.equalsIgnoreCase(key)) {
                return Optional.of(cosmetic);
            }
        }
        return Optional.empty();
    }
}
