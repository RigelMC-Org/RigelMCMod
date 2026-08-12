package org.rigelmc.protect.area;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.rank.PermissionGate;

/**
 * Owns every protected region's in-memory cache (rebuilt from the database once on enable,
 * kept in sync after every mutation) plus the overlap/priority policy - the concrete
 * improvement over TFM's real {@code ProtectArea}, which allows silent, unranked overlap
 * (see {@link AreaRegion#selectEffective}) and has no per-region owner/member bypass at all
 * (see {@link #isBypassing}).
 *
 * <p>Cache reads ({@link #list}, {@link #find}, {@link #findOverlapping}, {@link
 * #effectiveRegionAt}) are plain, synchronous, I/O-free - safe to call directly from any
 * thread, including the main thread inside a command's usage/validation path before ever
 * touching the database. Mutation methods below still perform real JDBC I/O and must be
 * called off the main thread (the {@code dbExecutor}-then-{@code runTask}-hop pattern
 * already used throughout this codebase - see {@code rank.RankAdminModule#executeSetRank}
 * for the template); {@code create}/{@code update} are additionally {@code synchronized}
 * since {@code data.RigelExecutors}'s database pool is 4 threads, not 1 - a genuine (if
 * low-stakes, admin-only, low-frequency) check-then-write race between two concurrent
 * region creations without this.</p>
 */
public final class ProtectAreaService {

    private final AreaDao areaDao;
    private final AreaMemberDao memberDao;
    private final AreaFlagDao flagDao;
    private final PermissionGate permissionGate;
    private final Map<Integer, AreaRegion> cache = new ConcurrentHashMap<>();

    public ProtectAreaService(
            @NotNull AreaDao areaDao, @NotNull AreaMemberDao memberDao, @NotNull AreaFlagDao flagDao,
            @NotNull PermissionGate permissionGate) {
        this.areaDao = areaDao;
        this.memberDao = memberDao;
        this.flagDao = flagDao;
        this.permissionGate = permissionGate;
    }

    /** Loads every region (with its members/flag overrides) from the database. Call once on enable. */
    public void loadPersistedState() throws SQLException {
        cache.clear();
        for (AreaRecord record : areaDao.findAll()) {
            cache.put(record.id(), buildRegion(record));
        }
    }

    private AreaRegion buildRegion(AreaRecord record) throws SQLException {
        Set<UUID> members = memberDao.findForArea(record.id());
        Map<AreaFlag, Boolean> flags = flagDao.findForArea(record.id());
        return new AreaRegion(record, members, flags);
    }

    // ---- reads (pure cache, no I/O) ------------------------------------------------------

    @NotNull
    public List<AreaRegion> list() {
        return cache.values().stream()
                .sorted(Comparator.comparing(region -> region.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    @NotNull
    public Optional<AreaRegion> find(@NotNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return cache.values().stream().filter(region -> region.name().toLowerCase(Locale.ROOT).equals(lower)).findFirst();
    }

    /**
     * @param excludeId a region to exclude from the results (the one being updated), or
     *     {@code null} for a fresh {@code create}
     * @return every enabled region in {@code world} whose bounds intersect the given box
     */
    @NotNull
    public List<AreaRegion> findOverlapping(
            @NotNull String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            @Nullable Integer excludeId) {
        List<AreaRegion> result = new ArrayList<>();
        for (AreaRegion region : cache.values()) {
            if (!region.enabled() || (excludeId != null && region.id() == excludeId)) {
                continue;
            }
            if (region.record().intersects(world, minX, minY, minZ, maxX, maxY, maxZ)) {
                result.add(region);
            }
        }
        return result;
    }

    /** @return whichever enabled region actually governs this point, per {@link AreaRegion#selectEffective}. */
    @NotNull
    public Optional<AreaRegion> effectiveRegionAt(@NotNull String world, int x, int y, int z) {
        List<AreaRegion> candidates = new ArrayList<>();
        for (AreaRegion region : cache.values()) {
            if (region.enabled() && region.contains(world, x, y, z)) {
                candidates.add(region);
            }
        }
        return Optional.ofNullable(AreaRegion.selectEffective(candidates));
    }

    /**
     * @return whether {@code player} bypasses {@code region}'s flags entirely - owner or
     *     explicit member, <b>or</b> Moderator+ (the same "any admin bypasses everything"
     *     floor TFM's real model has, kept here as a floor rather than the only mechanism).
     */
    public boolean isBypassing(@NotNull Player player, @NotNull AreaRegion region) {
        return region.isMemberOrOwner(player.getUniqueId())
                || permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator");
    }

    // ---- mutations (real JDBC I/O - call off the main thread) ---------------------------

    public enum CreateResult { OK, DUPLICATE_NAME, OVERLAP }

    public record CreateOutcome(@NotNull CreateResult result, @Nullable AreaRegion region, @NotNull List<AreaRegion> conflicts) {}

    /**
     * @param allowNest if {@code true} (the {@code -nest} flag), an overlapping region is
     *     allowed rather than rejected, and the new region is auto-assigned a priority one
     *     higher than every region it overlaps - see {@link AreaRegion#selectEffective}
     */
    @NotNull
    public synchronized CreateOutcome create(
            @NotNull String name, @NotNull String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            boolean allowNest, @Nullable UUID actor, long now) throws SQLException {
        if (find(name).isPresent()) {
            return new CreateOutcome(CreateResult.DUPLICATE_NAME, null, List.of());
        }
        List<AreaRegion> overlapping = findOverlapping(world, minX, minY, minZ, maxX, maxY, maxZ, null);
        if (!overlapping.isEmpty() && !allowNest) {
            return new CreateOutcome(CreateResult.OVERLAP, null, overlapping);
        }
        int priority = nextNestedPriority(overlapping);

        int id = areaDao.insert(name, world, "CUBOID", minX, minY, minZ, maxX, maxY, maxZ, priority, actor, actor, now);
        AreaRecord record = new AreaRecord(
                id, name, world, "CUBOID", minX, minY, minZ, maxX, maxY, maxZ, priority, true, actor, actor, now, actor, now);
        AreaRegion region = new AreaRegion(record, new HashSet<>(), new EnumMap<>(AreaFlag.class));
        cache.put(id, region);
        return new CreateOutcome(CreateResult.OK, region, List.of());
    }

    public enum UpdateResult { OK, OVERLAP }

    public record UpdateOutcome(@NotNull UpdateResult result, @Nullable AreaRegion region, @NotNull List<AreaRegion> conflicts) {}

    /** @param current the region being updated - callers resolve this via {@link #find} first. */
    @NotNull
    public synchronized UpdateOutcome update(
            @NotNull AreaRegion current, @NotNull String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            boolean allowNest, @Nullable UUID actor, long now) throws SQLException {
        List<AreaRegion> overlapping = findOverlapping(world, minX, minY, minZ, maxX, maxY, maxZ, current.id());
        if (!overlapping.isEmpty() && !allowNest) {
            return new UpdateOutcome(UpdateResult.OVERLAP, null, overlapping);
        }
        int priority = Math.max(current.priority(), nextNestedPriority(overlapping));

        areaDao.updateBounds(current.id(), world, minX, minY, minZ, maxX, maxY, maxZ, actor, now);
        if (priority != current.priority()) {
            areaDao.setPriority(current.id(), priority, actor, now);
        }
        AreaRecord updated = new AreaRecord(
                current.id(), current.name(), world, current.record().shapeType(), minX, minY, minZ, maxX, maxY, maxZ,
                priority, true, current.owner(), current.record().createdBy(), current.record().createdAt(), actor, now);
        AreaRegion region = new AreaRegion(updated, current.members(), current.flagOverrides());
        cache.put(current.id(), region);
        return new UpdateOutcome(UpdateResult.OK, region, List.of());
    }

    private static int nextNestedPriority(List<AreaRegion> overlapping) {
        return overlapping.isEmpty() ? 0 : overlapping.stream().mapToInt(AreaRegion::priority).max().orElse(0) + 1;
    }

    public void rename(@NotNull AreaRegion current, @NotNull String newName, @Nullable UUID actor, long now) throws SQLException {
        areaDao.rename(current.id(), newName, actor, now);
        AreaRecord record = current.record();
        AreaRecord renamed = new AreaRecord(
                record.id(), newName, record.world(), record.shapeType(), record.minX(), record.minY(), record.minZ(),
                record.maxX(), record.maxY(), record.maxZ(), record.priority(), record.enabled(), record.owner(),
                record.createdBy(), record.createdAt(), actor, now);
        cache.put(current.id(), new AreaRegion(renamed, current.members(), current.flagOverrides()));
    }

    public void setPriority(@NotNull AreaRegion current, int priority, @Nullable UUID actor, long now) throws SQLException {
        areaDao.setPriority(current.id(), priority, actor, now);
        cache.put(current.id(), withPriority(current, priority, actor, now));
    }

    private static AreaRegion withPriority(AreaRegion current, int priority, @Nullable UUID actor, long now) {
        AreaRecord record = current.record();
        AreaRecord updated = new AreaRecord(
                record.id(), record.name(), record.world(), record.shapeType(), record.minX(), record.minY(), record.minZ(),
                record.maxX(), record.maxY(), record.maxZ(), priority, record.enabled(), record.owner(),
                record.createdBy(), record.createdAt(), actor, now);
        return new AreaRegion(updated, current.members(), current.flagOverrides());
    }

    public void setOwner(@NotNull AreaRegion current, @Nullable UUID owner, @Nullable UUID actor, long now) throws SQLException {
        areaDao.setOwner(current.id(), owner, actor, now);
        AreaRecord record = current.record();
        AreaRecord updated = new AreaRecord(
                record.id(), record.name(), record.world(), record.shapeType(), record.minX(), record.minY(), record.minZ(),
                record.maxX(), record.maxY(), record.maxZ(), record.priority(), record.enabled(), owner,
                record.createdBy(), record.createdAt(), actor, now);
        cache.put(current.id(), new AreaRegion(updated, current.members(), current.flagOverrides()));
    }

    public void setEnabled(@NotNull AreaRegion current, boolean enabled, @Nullable UUID actor, long now) throws SQLException {
        areaDao.setEnabled(current.id(), enabled, actor, now);
        AreaRecord record = current.record();
        AreaRecord updated = new AreaRecord(
                record.id(), record.name(), record.world(), record.shapeType(), record.minX(), record.minY(), record.minZ(),
                record.maxX(), record.maxY(), record.maxZ(), record.priority(), enabled, record.owner(),
                record.createdBy(), record.createdAt(), actor, now);
        cache.put(current.id(), new AreaRegion(updated, current.members(), current.flagOverrides()));
    }

    /**
     * Deletes {@code region} and cascades to its member/flag-override rows - {@code
     * rigel_protect_areas} has no {@code ON DELETE CASCADE} (confirmed by reading the
     * migration directly), so without this an orphaned row in {@code
     * rigel_protect_area_members}/{@code rigel_protect_area_flags} would sit around
     * forever, unreachable through any cache path but never cleaned up. Harmless at the
     * low frequency admin {@code /protectarea delete} calls this (small, cosmetic amount of
     * dead data), but guild plot disbands (a literal delete-of-this-exact-kind-of-row) churn
     * this dramatically more - worth fixing at the root rather than leaving a slow leak.
     */
    public void delete(@NotNull AreaRegion region) throws SQLException {
        memberDao.deleteForArea(region.id());
        flagDao.deleteForArea(region.id());
        areaDao.delete(region.id());
        cache.remove(region.id());
    }

    /** Wipes every region in one shot (including every member/flag row - see {@link #delete}'s javadoc) - backs {@code /protectarea clear confirm}. */
    public void clearAll() throws SQLException {
        memberDao.deleteAll();
        flagDao.deleteAll();
        areaDao.deleteAll();
        cache.clear();
    }

    /** {@code default} (a {@code null} value) clears the override, reverting to {@link AreaFlag#defaultValue()}. */
    public void setFlag(@NotNull AreaRegion current, @NotNull AreaFlag flag, @Nullable Boolean value) throws SQLException {
        Map<AreaFlag, Boolean> overrides = new EnumMap<>(current.flagOverrides());
        if (value != null) {
            flagDao.setOverride(current.id(), flag, value);
            overrides.put(flag, value);
        } else {
            flagDao.clearOverride(current.id(), flag);
            overrides.remove(flag);
        }
        cache.put(current.id(), new AreaRegion(current.record(), current.members(), overrides));
    }

    public void addMember(@NotNull AreaRegion current, @NotNull UUID member, @Nullable UUID actor, long now) throws SQLException {
        memberDao.add(current.id(), member, actor, now);
        Set<UUID> members = new HashSet<>(current.members());
        members.add(member);
        cache.put(current.id(), new AreaRegion(current.record(), members, current.flagOverrides()));
    }

    public void removeMember(@NotNull AreaRegion current, @NotNull UUID member) throws SQLException {
        memberDao.remove(current.id(), member);
        Set<UUID> members = new HashSet<>(current.members());
        members.remove(member);
        cache.put(current.id(), new AreaRegion(current.record(), members, current.flagOverrides()));
    }
}
