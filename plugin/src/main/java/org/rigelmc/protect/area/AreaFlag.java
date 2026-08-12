package org.rigelmc.protect.area;

import org.jetbrains.annotations.NotNull;

/**
 * A protected region's per-region toggles - the concrete answer to "let one region allow
 * PvP while another doesn't," which TFM's real {@code ProtectArea} has no way to express at
 * all (it only has three <i>global</i> config booleans - {@code protect_players}, {@code
 * block_potions}, {@code block_items} - that apply identically to every region or none).
 *
 * <p>Deliberately a small, fixed set of six, not an open-ended registry like WorldGuard's
 * flag system - each has a hardcoded coded default here rather than a config-driven one, so
 * {@code config.yml} doesn't grow a whole parallel flag-defaults section for a feature whose
 * entire premise is staying smaller than that. A region's actual effective value is this
 * default unless overridden per-region (see {@link AreaFlagDao}/{@link AreaRegion#effectiveFlag}).</p>
 */
public enum AreaFlag {

    /**
     * Block break/place, bucket empty/fill, sign edits, piston push/pull across the
     * boundary, hanging (item frame/painting) place/break, vehicle destruction, and fire
     * ignite/spread/burn/fade - every "the world itself changed" concern folded into one
     * flag rather than TFM's implicit "every one of these is always protected" default.
     */
    BUILD("build", false),

    /**
     * Player-vs-player damage, and splash/lingering-potion/area-effect-cloud damage that
     * targets a player. TFM's separate {@code block_potions} global toggle folds in here -
     * it's a subset of "can players hurt each other in this region," not an independent
     * concern.
     */
    PVP("pvp", true),

    /** Explosion (TNT, creeper, bed/respawn-anchor) block removal inside the region. */
    EXPLOSIONS("explosions", false),

    /**
     * Can a non-bypassing player even walk into the region at all. New - TFM has no
     * equivalent; its protection only ever stops modification, never entry itself.
     */
    ENTRY("entry", true),

    /**
     * Item-entity pickup, hopper pickup, and hopper/container-to-container transfer.
     * TFM only covers the first two ({@code EntityPickupItemEvent}/{@code
     * InventoryPickupItemEvent}) - it has no {@code InventoryMoveItemEvent} handler at all,
     * meaning a hopper chain can silently siphon items out of a "protected" region in real
     * TFM. Covered here under this same flag.
     */
    ITEM_PICKUP("item-pickup", true),

    /** Right-click/physical interaction with blocks and entities inside the region. */
    INTERACT("interact", true),

    /**
     * Spawner-egg use, mob-spawner block spawns, and dispenser-fired spawn eggs inside the
     * region - the actual griefing/lag vectors, not natural or ambient spawns (a mob that
     * simply walks in from outside, or spawns naturally in the dark, is left alone). New -
     * TFM has no equivalent; added specifically for guild plot protection (see {@code
     * guild.plot.GuildPlotWorldService}), default ALLOW at the general {@code /protectarea}
     * level so this flag is a no-op for every region created before it existed.
     */
    MOB_SPAWN("mob-spawn", true);

    private final String key;
    private final boolean defaultValue;

    AreaFlag(String key, boolean defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    /** @return the stable, lowercase, command-facing key (e.g. {@code "item-pickup"}) - also the DB {@code flag_key}. */
    @NotNull
    public String key() {
        return key;
    }

    /** @return whether this flag is ALLOW (true) or DENY (false) absent an explicit per-region override. */
    public boolean defaultValue() {
        return defaultValue;
    }

    /** @return the flag whose {@link #key()} matches {@code key} (case-insensitive), or {@code null} if none does. */
    public static AreaFlag byKey(@NotNull String key) {
        for (AreaFlag flag : values()) {
            if (flag.key.equalsIgnoreCase(key)) {
                return flag;
            }
        }
        return null;
    }
}
