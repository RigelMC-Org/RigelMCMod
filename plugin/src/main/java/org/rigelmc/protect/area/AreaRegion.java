package org.rigelmc.protect.area;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An {@link AreaRecord} plus its resolved members and flag overrides - the in-memory,
 * runtime-queryable form {@link ProtectAreaService} caches one of per region. Deliberately
 * free of any Bukkit or {@code PermissionGate} dependency (only ever touches {@link
 * AreaRecord}, {@link AreaFlag}, and plain {@link UUID}s) so containment, volume, and the
 * overlap tie-break can be unit-tested without a server - see {@code AreaRegionTest}.
 *
 * <p>Membership here means "bypasses this one region's flags" only - it is <b>not</b> the
 * full bypass check ({@link ProtectAreaService#isBypassing} additionally lets any
 * Moderator+ through regardless of membership, which needs {@code PermissionGate} and so
 * can't live here).</p>
 */
public final class AreaRegion {

    private final AreaRecord record;
    private final Set<UUID> members;
    private final Map<AreaFlag, Boolean> flagOverrides;

    public AreaRegion(@NotNull AreaRecord record, @NotNull Set<UUID> members, @NotNull Map<AreaFlag, Boolean> flagOverrides) {
        this.record = record;
        this.members = members;
        this.flagOverrides = flagOverrides;
    }

    @NotNull
    public AreaRecord record() {
        return record;
    }

    public int id() {
        return record.id();
    }

    @NotNull
    public String name() {
        return record.name();
    }

    public boolean enabled() {
        return record.enabled();
    }

    public int priority() {
        return record.priority();
    }

    public long volume() {
        return record.volume();
    }

    @Nullable
    public UUID owner() {
        return record.owner();
    }

    @NotNull
    public Set<UUID> members() {
        return members;
    }

    public boolean contains(@NotNull String world, int x, int y, int z) {
        return record.contains(world, x, y, z);
    }

    /** @return this flag's override for this region if one is set, otherwise {@link AreaFlag#defaultValue()}. */
    public boolean effectiveFlag(@NotNull AreaFlag flag) {
        Boolean override = flagOverrides.get(flag);
        return override != null ? override : flag.defaultValue();
    }

    /** @return the raw per-flag override map (no defaults filled in) - for {@code info}'s display and testing. */
    @NotNull
    public Map<AreaFlag, Boolean> flagOverrides() {
        return flagOverrides;
    }

    /**
     * @return whether {@code uuid} is this region's owner or an explicit member - does
     *     <b>not</b> check staff rank, see this class's javadoc and {@link
     *     ProtectAreaService#isBypassing} for the full check.
     */
    public boolean isMemberOrOwner(@Nullable UUID uuid) {
        return uuid != null && (uuid.equals(record.owner()) || members.contains(uuid));
    }

    /**
     * Resolves which region actually governs a point when more than one enabled region
     * contains it - the overlap precedence policy TFM's real {@code ProtectArea} has no
     * equivalent of at all (it allows silent, unranked overlap). Highest {@link
     * AreaRecord#priority()} wins; ties broken by smallest {@link #volume()} (the
     * WorldGuard convention - a deliberately nested sub-region should win over the region
     * it's nested inside); a final tie broken by most recently created.
     *
     * @param candidates every enabled region already confirmed to contain the point in
     *     question - this method does no containment checking itself
     * @return the winning region, or {@code null} if {@code candidates} is empty
     */
    @Nullable
    public static AreaRegion selectEffective(@NotNull List<AreaRegion> candidates) {
        return candidates.stream()
                .max(Comparator
                        .comparingInt(AreaRegion::priority)
                        .thenComparing(Comparator.comparingLong(AreaRegion::volume).reversed())
                        .thenComparingLong(region -> region.record().createdAt()))
                .orElse(null);
    }
}
