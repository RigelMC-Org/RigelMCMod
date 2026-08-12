package org.rigelmc.guild;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.chat.ImpersonationGuard;
import org.rigelmc.guild.plot.GuildPlotWorldService;

/**
 * Orchestrates {@link GuildDao}/{@link GuildMemberDao} plus an in-memory cache ({@code
 * Map<Integer, GuildRoster>} + a {@code Map<UUID, Integer>} reverse member index),
 * rebuilt on enable and kept in sync on every mutation - same shape as {@code
 * protect.area.ProtectAreaService}, for the same reason: command-layer permission checks
 * (is the sender OWNER/OFFICER of <i>their</i> guild) need to be fast and synchronous, not
 * a DB round trip per check.
 *
 * <p>Deliberately low-level: this class performs mutations and keeps the cache/DB
 * consistent, but doesn't itself decide <i>who's allowed to</i> kick/promote/demote whom -
 * that business logic (role comparisons, "can't kick an OFFICER as an OFFICER", etc.) lives
 * in {@link GuildCommand}, which already has the roster in hand from {@link #rosterFor}
 * before calling into any mutation here. Matches {@code ProtectAreaService.removeMember}
 * being a pure low-level op while {@code ProtectAreaCommand} does its own permission check
 * first.</p>
 *
 * <p><b>Plot integration</b> ({@code guild.plot.GuildPlotWorldService}): {@link #create}
 * assigns a plot right after the guild + owner-membership rows are inserted; {@link
 * #addMember}/{@link #removeMember}/{@link #transferOwner}/{@link #disband} each make the
 * matching plot-membership/-ownership/-deletion call afterward. {@code GuildPlotWorldService}
 * itself is entirely Bukkit/config-free (see its own javadoc), which is what keeps this
 * class - and {@code GuildServiceTest} - free of any MockBukkit dependency despite now
 * driving plot assignment on every create.</p>
 */
public final class GuildService {

    private final GuildDao guildDao;
    private final GuildMemberDao guildMemberDao;
    private final GuildPlotWorldService guildPlotWorldService;
    private final Map<Integer, GuildRoster> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> guildIdByMember = new ConcurrentHashMap<>();

    public GuildService(
            @NotNull GuildDao guildDao, @NotNull GuildMemberDao guildMemberDao,
            @NotNull GuildPlotWorldService guildPlotWorldService) {
        this.guildDao = guildDao;
        this.guildMemberDao = guildMemberDao;
        this.guildPlotWorldService = guildPlotWorldService;
    }

    /** Rebuilds the in-memory cache from the database - call once on enable, before any command can run. Blocking. */
    public void loadPersistedState() throws SQLException {
        List<GuildRecord> records = guildDao.findAll();
        List<GuildMemberRecord> allMembers = guildMemberDao.findAll();

        Map<Integer, Map<UUID, GuildRole>> membersByGuild = new HashMap<>();
        Map<UUID, Integer> memberIndex = new HashMap<>();
        for (GuildMemberRecord member : allMembers) {
            membersByGuild.computeIfAbsent(member.guildId(), k -> new HashMap<>()).put(member.memberUuid(), member.role());
            memberIndex.put(member.memberUuid(), member.guildId());
        }

        Map<Integer, GuildRoster> rosters = new HashMap<>();
        for (GuildRecord record : records) {
            rosters.put(record.id(), new GuildRoster(record, membersByGuild.getOrDefault(record.id(), Map.of())));
        }

        byId.clear();
        byId.putAll(rosters);
        guildIdByMember.clear();
        guildIdByMember.putAll(memberIndex);
    }

    public enum CreateResult { CREATED, INVALID_NAME, NAME_TAKEN, ALREADY_IN_GUILD }

    public record CreateOutcome(@NotNull CreateResult result, @Nullable GuildRoster roster) {
    }

    /**
     * @param minLength/{@code maxLength} from {@code guild.name-min-length}/{@code
     *     -max-length} - passed in rather than read from config here, so this class stays
     *     Bukkit/config-free and unit-testable
     * @param plotWorldName/{@code plotGridSettings} from {@code guild.plotworld.*} - see
     *     {@link GuildPlotWorldService.PlotGridSettings#fromConfig}. A plot-assignment
     *     failure (see {@link GuildPlotWorldService#assignPlotForGuild}'s javadoc) never
     *     fails guild creation itself - an operator can already tell something's wrong from
     *     the warning {@code GuildPlotWorldService} logs, and a guild without a plot is a
     *     safe, recoverable state (an admin fix or a later retry can assign one), whereas
     *     refusing to create the guild at all over an infrastructure hiccup would be worse.
     */
    @NotNull
    public synchronized CreateOutcome create(
            @NotNull String name, @NotNull UUID ownerUuid, int minLength, int maxLength,
            @NotNull String plotWorldName, @NotNull GuildPlotWorldService.PlotGridSettings plotGridSettings, long now)
            throws SQLException {
        if (!isValidName(name, minLength, maxLength)) {
            return new CreateOutcome(CreateResult.INVALID_NAME, null);
        }
        if (guildIdByMember.containsKey(ownerUuid)) {
            return new CreateOutcome(CreateResult.ALREADY_IN_GUILD, null);
        }
        String nameLower = name.toLowerCase(Locale.ROOT);
        if (guildDao.findByNameLower(nameLower).isPresent()) {
            return new CreateOutcome(CreateResult.NAME_TAKEN, null);
        }

        int id = guildDao.insert(name, ownerUuid, now);
        guildMemberDao.add(id, ownerUuid, GuildRole.OWNER, now);

        GuildPlotWorldService.PlotAssignmentOutcome plot =
                guildPlotWorldService.assignPlotForGuild(id, ownerUuid, plotWorldName, plotGridSettings, now);
        if (plot.assigned()) {
            guildDao.setPlot(id, plot.plotAreaId(), plot.plotSlotIndex(), now);
        }

        GuildRecord record = new GuildRecord(id, name, nameLower, ownerUuid, plot.plotAreaId(), plot.plotSlotIndex(), now, now);
        GuildRoster roster = new GuildRoster(record, Map.of(ownerUuid, GuildRole.OWNER));
        byId.put(id, roster);
        guildIdByMember.put(ownerUuid, id);
        return new CreateOutcome(CreateResult.CREATED, roster);
    }

    /** @return {@code true} if {@code name} passes length bounds and isn't a staff-rank impersonation attempt. */
    public static boolean isValidName(@NotNull String name, int minLength, int maxLength) {
        String trimmed = name.strip();
        return trimmed.length() >= minLength && trimmed.length() <= maxLength && !ImpersonationGuard.looksLikeImpersonation(trimmed);
    }

    /**
     * Adds {@code uuid} to {@code guildId} at {@code role} - caller must have already
     * verified they're not already in a guild. Also grants plot bypass, if this guild has
     * an assigned plot (see {@link GuildPlotWorldService#syncMemberAdded} - a silent no-op
     * otherwise).
     */
    public synchronized void addMember(int guildId, @NotNull UUID uuid, @NotNull GuildRole role, long now) throws SQLException {
        guildMemberDao.add(guildId, uuid, role, now);
        GuildRoster roster = byId.get(guildId);
        if (roster != null) {
            Map<UUID, GuildRole> updated = new HashMap<>(roster.members());
            updated.put(uuid, role);
            byId.put(guildId, roster.withMembers(updated));
        }
        guildIdByMember.put(uuid, guildId);
        guildPlotWorldService.syncMemberAdded(guildId, uuid, null, now);
    }

    /**
     * Removes {@code uuid} from {@code guildId} - used by both kick and voluntary leave.
     * Never call this for the current OWNER; use {@link #transferOwner} or {@link #disband}
     * instead. Also revokes plot bypass, if this guild has an assigned plot (see {@link
     * GuildPlotWorldService#syncMemberRemoved} - a silent no-op otherwise).
     */
    public synchronized void removeMember(int guildId, @NotNull UUID uuid) throws SQLException {
        guildMemberDao.remove(guildId, uuid);
        GuildRoster roster = byId.get(guildId);
        if (roster != null) {
            Map<UUID, GuildRole> updated = new HashMap<>(roster.members());
            updated.remove(uuid);
            byId.put(guildId, roster.withMembers(updated));
        }
        guildIdByMember.remove(uuid);
        guildPlotWorldService.syncMemberRemoved(guildId, uuid);
    }

    public synchronized void updateRole(int guildId, @NotNull UUID uuid, @NotNull GuildRole role) throws SQLException {
        guildMemberDao.updateRole(guildId, uuid, role);
        GuildRoster roster = byId.get(guildId);
        if (roster != null) {
            Map<UUID, GuildRole> updated = new HashMap<>(roster.members());
            updated.put(uuid, role);
            byId.put(guildId, roster.withMembers(updated));
        }
    }

    /**
     * Transfers ownership - the old owner becomes an OFFICER (not removed), the new owner
     * is promoted to OWNER. Also moves plot ownership to {@code newOwner} (see {@link
     * GuildPlotWorldService#syncOwner}) and, since being the plot's area <i>owner</i> was
     * {@code oldOwner}'s only source of plot bypass (they were never separately added as an
     * explicit plot <i>member</i> - see {@link #create}), explicitly adds them as one now so
     * they don't lose plot access entirely just for stepping down to OFFICER.
     */
    public synchronized void transferOwner(int guildId, @NotNull UUID oldOwner, @NotNull UUID newOwner, long now) throws SQLException {
        guildDao.setOwner(guildId, newOwner, now);
        guildMemberDao.updateRole(guildId, oldOwner, GuildRole.OFFICER);
        guildMemberDao.updateRole(guildId, newOwner, GuildRole.OWNER);
        guildPlotWorldService.syncOwner(guildId, newOwner, oldOwner, now);
        guildPlotWorldService.syncMemberAdded(guildId, oldOwner, newOwner, now);

        GuildRoster roster = byId.get(guildId);
        if (roster != null) {
            Map<UUID, GuildRole> updated = new HashMap<>(roster.members());
            updated.put(oldOwner, GuildRole.OFFICER);
            updated.put(newOwner, GuildRole.OWNER);
            GuildRecord newRecord = new GuildRecord(
                    roster.record().id(), roster.record().name(), roster.record().nameLower(), newOwner,
                    roster.record().plotAreaId(), roster.record().plotSlotIndex(), roster.record().createdAt(), now);
            byId.put(guildId, new GuildRoster(newRecord, updated));
        }
    }

    /** Deletes the guild, every member row for it, and its plot region (if it has one) - freeing the plot slot for reuse. */
    public synchronized void disband(int guildId) throws SQLException {
        GuildRoster existing = byId.get(guildId);
        guildMemberDao.removeAllForGuild(guildId);
        guildDao.delete(guildId);
        guildPlotWorldService.releasePlot(guildId, existing != null ? existing.record().plotSlotIndex() : null);

        GuildRoster roster = byId.remove(guildId);
        if (roster != null) {
            for (UUID member : roster.members().keySet()) {
                guildIdByMember.remove(member);
            }
        }
    }

    /**
     * Full plotworld reset (user-requested) - the DB/protection half of {@code
     * /wipeguildplots confirm}. Deletes every guild's existing plot region and the boundary
     * region ({@link GuildPlotWorldService#resetAll}), recreates the boundary fresh <i>before</i>
     * any plot (so priority nesting is deterministic - see {@link
     * GuildPlotWorldService#assignPlotForGuild}'s javadoc), then reassigns every existing
     * guild a brand-new, empty plot and re-syncs every non-owner member's bypass onto it
     * (the owner's bypass comes from area ownership, set directly by {@code
     * assignPlotForGuild} itself). Does not touch the Bukkit world itself - the caller wipes
     * that separately (see {@code GuildModule}'s {@code /wipeguildplots} command) before
     * calling this, matching {@link #create}'s own "a plot-assignment failure never fails
     * the guild-level operation" tolerance if any individual reassignment comes back empty.
     */
    public synchronized void resetPlotWorld(
            @NotNull String plotWorldName, @NotNull GuildPlotWorldService.PlotGridSettings plotGridSettings, long now)
            throws SQLException {
        guildPlotWorldService.resetAll();
        guildPlotWorldService.ensureBoundaryProtectionExists(plotWorldName, plotGridSettings, now);

        for (GuildRoster roster : byId.values()) {
            GuildPlotWorldService.PlotAssignmentOutcome plot = guildPlotWorldService.assignPlotForGuild(
                    roster.id(), roster.ownerUuid(), plotWorldName, plotGridSettings, now);
            Integer plotAreaId = plot.assigned() ? plot.plotAreaId() : null;
            Integer plotSlotIndex = plot.assigned() ? plot.plotSlotIndex() : null;
            if (plot.assigned()) {
                guildDao.setPlot(roster.id(), plotAreaId, plotSlotIndex, now);
                for (UUID member : roster.members().keySet()) {
                    if (!member.equals(roster.ownerUuid())) {
                        guildPlotWorldService.syncMemberAdded(roster.id(), member, null, now);
                    }
                }
            } else {
                guildDao.clearPlot(roster.id(), now);
            }

            GuildRecord oldRecord = roster.record();
            GuildRecord newRecord = new GuildRecord(
                    oldRecord.id(), oldRecord.name(), oldRecord.nameLower(), oldRecord.ownerUuid(),
                    plotAreaId, plotSlotIndex, oldRecord.createdAt(), now);
            byId.put(roster.id(), roster.withRecord(newRecord));
        }
    }

    @NotNull
    public Optional<GuildRoster> rosterFor(@NotNull UUID memberUuid) {
        Integer guildId = guildIdByMember.get(memberUuid);
        return guildId == null ? Optional.empty() : Optional.ofNullable(byId.get(guildId));
    }

    @NotNull
    public Optional<GuildRoster> rosterByName(@NotNull String name) {
        String nameLower = name.toLowerCase(Locale.ROOT);
        return byId.values().stream().filter(r -> r.record().nameLower().equals(nameLower)).findFirst();
    }

    @NotNull
    public Optional<GuildRoster> rosterById(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** @return every guild, sorted by name - for {@code /guild list}. */
    @NotNull
    public List<GuildRoster> list() {
        return byId.values().stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }
}
