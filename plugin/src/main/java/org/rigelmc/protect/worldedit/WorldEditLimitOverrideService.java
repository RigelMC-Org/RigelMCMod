package org.rigelmc.protect.worldedit;

import org.jetbrains.annotations.Nullable;

/**
 * In-memory, session-only overrides for {@code protect.worldedit.radius-max}/{@code
 * protect.worldedit.limits.max-volume} - user-requested: a command to raise or lower the
 * WorldEdit abuse limits without editing {@code config.yml} and restarting (e.g. reacting
 * to an active lag incident right now, or temporarily raising it for a trusted build
 * project). Deliberately never written back to {@code config.yml} - that file is
 * hand-maintained and heavily comment-annotated throughout this project; {@code
 * FileConfiguration#save()} would silently rewrite the whole file and strip every comment
 * in it, a far worse outcome than the override simply resetting on restart. Same
 * "session-only, no persistence" shape as {@code vanish.VanishService}/{@code
 * guild.plot.GuildPlotBypassService} - a fresh boot always starts back at whatever {@code
 * config.yml} actually says.
 *
 * <p>{@code null} means "no override - use the config.yml default," not "unlimited"; use
 * {@link #setRadiusMax}/{@link #setVolumeMax} with {@code 0} for that (matching {@code
 * RigelConfig#worldEditRadiusMax}'s own existing "{@code <= 0} disables the check"
 * convention).</p>
 */
public final class WorldEditLimitOverrideService {

    private volatile Integer radiusMaxOverride;
    private volatile Integer volumeMaxOverride;

    @Nullable
    public Integer radiusMaxOverride() {
        return radiusMaxOverride;
    }

    @Nullable
    public Integer volumeMaxOverride() {
        return volumeMaxOverride;
    }

    /** @return {@code configDefault} unless an override is currently set, in which case the override. */
    public int effectiveRadiusMax(int configDefault) {
        Integer override = radiusMaxOverride;
        return override != null ? override : configDefault;
    }

    /** @return {@code configDefault} unless an override is currently set, in which case the override. */
    public int effectiveVolumeMax(int configDefault) {
        Integer override = volumeMaxOverride;
        return override != null ? override : configDefault;
    }

    public void setRadiusMax(int value) {
        radiusMaxOverride = value;
    }

    public void setVolumeMax(int value) {
        volumeMaxOverride = value;
    }

    public void clearRadiusMax() {
        radiusMaxOverride = null;
    }

    public void clearVolumeMax() {
        volumeMaxOverride = null;
    }

    public void clearAll() {
        radiusMaxOverride = null;
        volumeMaxOverride = null;
    }
}
