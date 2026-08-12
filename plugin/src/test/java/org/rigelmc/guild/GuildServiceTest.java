package org.rigelmc.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;
import org.rigelmc.guild.plot.GuildPlotWorldService;
import org.rigelmc.protect.area.AreaDao;
import org.rigelmc.protect.area.AreaFlag;
import org.rigelmc.protect.area.AreaFlagDao;
import org.rigelmc.protect.area.AreaMemberDao;
import org.rigelmc.protect.area.AreaRegion;
import org.rigelmc.protect.area.ProtectAreaService;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.RankRepository;
import org.rigelmc.rank.RankService;

/**
 * Exercises the full create/join/leave/disband lifecycle - including plot assignment,
 * membership sync, and release - against a real {@link ProtectAreaService} and a real
 * temp-file SQLite database, no MockBukkit, matching {@code ProtectAreaServiceTest}'s own
 * established "real service, Proxy-faked {@code Plugin}" precedent (needed here only
 * because {@link ProtectAreaService} itself requires a {@link PermissionGate}).
 */
class GuildServiceTest {

    private static final GuildPlotWorldService.PlotGridSettings GRID_SETTINGS =
            new GuildPlotWorldService.PlotGridSettings(16, 4, 10, 0, 100);
    private static final String PLOT_WORLD = "guildplots";

    private HikariDataSource dataSource;
    private GuildDao guildDao;
    private GuildMemberDao guildMemberDao;
    private ProtectAreaService protectAreaService;
    private GuildPlotWorldService guildPlotWorldService;
    private GuildService guildService;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.guildDao = new GuildDao(dataSource);
        this.guildMemberDao = new GuildMemberDao(dataSource);

        RankService rankService = new RankService(new RankRepository(dataSource), new org.rigelmc.data.dao.PlayerDao(dataSource));
        rankService.initialize();
        Plugin fakePlugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(
                            "Unexpected call to Plugin#" + method.getName() + " - this test never needs a live plugin");
                });
        PermissionGate permissionGate = new PermissionGate(fakePlugin, rankService);
        this.protectAreaService = new ProtectAreaService(
                new AreaDao(dataSource), new AreaMemberDao(dataSource), new AreaFlagDao(dataSource), permissionGate);

        this.guildPlotWorldService = new GuildPlotWorldService(guildDao, protectAreaService);
        this.guildService = new GuildService(guildDao, guildMemberDao, guildPlotWorldService);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    private GuildService.CreateOutcome create(String name, UUID owner, long now) throws Exception {
        return guildService.create(name, owner, 3, 24, PLOT_WORLD, GRID_SETTINGS, now);
    }

    @Test
    void createMakesTheOwnerTheSoleOwnerMember() throws Exception {
        UUID owner = UUID.randomUUID();

        GuildService.CreateOutcome outcome = create("Astra", owner, System.currentTimeMillis());

        assertEquals(GuildService.CreateResult.CREATED, outcome.result());
        GuildRoster roster = outcome.roster();
        assertEquals(GuildRole.OWNER, roster.roleOf(owner));
        assertEquals(owner, roster.ownerUuid());
        assertTrue(guildService.rosterFor(owner).isPresent());
    }

    @Test
    void createRejectsAnInvalidLengthName() throws Exception {
        GuildService.CreateOutcome outcome = create("ab", UUID.randomUUID(), System.currentTimeMillis());
        assertEquals(GuildService.CreateResult.INVALID_NAME, outcome.result());
    }

    @Test
    void createRejectsADuplicateNameCaseInsensitively() throws Exception {
        long now = System.currentTimeMillis();
        create("Astra", UUID.randomUUID(), now);

        GuildService.CreateOutcome outcome = create("astra", UUID.randomUUID(), now);

        assertEquals(GuildService.CreateResult.NAME_TAKEN, outcome.result());
    }

    @Test
    void createRejectsAPlayerAlreadyInAGuild() throws Exception {
        long now = System.currentTimeMillis();
        UUID owner = UUID.randomUUID();
        create("Astra", owner, now);

        GuildService.CreateOutcome outcome = create("Nova", owner, now);

        assertEquals(GuildService.CreateResult.ALREADY_IN_GUILD, outcome.result());
    }

    // ---- plot assignment --------------------------------------------------------------------

    @Test
    void createAssignsAPlotOwnedByTheGuildOwner() throws Exception {
        UUID owner = UUID.randomUUID();

        GuildRoster roster = create("Astra", owner, System.currentTimeMillis()).roster();

        assertNotNull(roster.record().plotAreaId());
        assertNotNull(roster.record().plotSlotIndex());
        assertEquals(0, roster.record().plotSlotIndex());
        AreaRegion plot = protectAreaService.find("guild-plot-" + roster.id()).orElseThrow();
        assertEquals(owner, plot.owner());
    }

    @Test
    void createdPlotDeniesBuildExplosionsMobSpawnInteractAndPvp() throws Exception {
        GuildRoster roster = create("Astra", UUID.randomUUID(), System.currentTimeMillis()).roster();

        AreaRegion plot = protectAreaService.find("guild-plot-" + roster.id()).orElseThrow();
        assertFalse(plot.effectiveFlag(AreaFlag.BUILD));
        assertFalse(plot.effectiveFlag(AreaFlag.EXPLOSIONS));
        assertFalse(plot.effectiveFlag(AreaFlag.MOB_SPAWN));
        assertFalse(plot.effectiveFlag(AreaFlag.INTERACT));
        assertFalse(plot.effectiveFlag(AreaFlag.PVP));
        // ENTRY stays at its allow default - plots remain visitable.
        assertTrue(plot.effectiveFlag(AreaFlag.ENTRY));
    }

    @Test
    void successiveGuildsGetDistinctNonOverlappingSlots() throws Exception {
        long now = System.currentTimeMillis();
        GuildRoster first = create("Astra", UUID.randomUUID(), now).roster();
        GuildRoster second = create("Nova", UUID.randomUUID(), now).roster();

        assertEquals(0, first.record().plotSlotIndex());
        assertEquals(1, second.record().plotSlotIndex());
    }

    // ---- membership/ownership plot sync -----------------------------------------------------

    @Test
    void addMemberThenRemoveMemberKeepsCacheAndPlotBypassInSync() throws Exception {
        long now = System.currentTimeMillis();
        GuildRoster roster = create("Astra", UUID.randomUUID(), now).roster();
        UUID member = UUID.randomUUID();

        guildService.addMember(roster.id(), member, GuildRole.MEMBER, now);
        assertTrue(guildService.rosterFor(member).isPresent());
        assertEquals(GuildRole.MEMBER, guildService.rosterFor(member).orElseThrow().roleOf(member));
        AreaRegion plotAfterAdd = protectAreaService.find("guild-plot-" + roster.id()).orElseThrow();
        assertTrue(plotAfterAdd.isMemberOrOwner(member));

        guildService.removeMember(roster.id(), member);
        assertFalse(guildService.rosterFor(member).isPresent());
        AreaRegion plotAfterRemove = protectAreaService.find("guild-plot-" + roster.id()).orElseThrow();
        assertFalse(plotAfterRemove.isMemberOrOwner(member));
    }

    @Test
    void updateRoleChangesTheCachedRole() throws Exception {
        long now = System.currentTimeMillis();
        GuildRoster roster = create("Astra", UUID.randomUUID(), now).roster();
        UUID member = UUID.randomUUID();
        guildService.addMember(roster.id(), member, GuildRole.MEMBER, now);

        guildService.updateRole(roster.id(), member, GuildRole.OFFICER);

        assertEquals(GuildRole.OFFICER, guildService.rosterFor(member).orElseThrow().roleOf(member));
    }

    @Test
    void transferOwnerDemotesTheOldOwnerToOfficerAndPromotesTheNewOwner() throws Exception {
        long now = System.currentTimeMillis();
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        GuildRoster roster = create("Astra", oldOwner, now).roster();
        guildService.addMember(roster.id(), newOwner, GuildRole.MEMBER, now);

        guildService.transferOwner(roster.id(), oldOwner, newOwner, now + 1);

        GuildRoster updated = guildService.rosterFor(newOwner).orElseThrow();
        assertEquals(GuildRole.OFFICER, updated.roleOf(oldOwner));
        assertEquals(GuildRole.OWNER, updated.roleOf(newOwner));
        assertEquals(newOwner, updated.ownerUuid());
    }

    @Test
    void transferOwnerMovesPlotOwnershipAndKeepsTheOldOwnerAsAPlotMember() throws Exception {
        long now = System.currentTimeMillis();
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        GuildRoster roster = create("Astra", oldOwner, now).roster();
        guildService.addMember(roster.id(), newOwner, GuildRole.MEMBER, now);

        guildService.transferOwner(roster.id(), oldOwner, newOwner, now + 1);

        AreaRegion plot = protectAreaService.find("guild-plot-" + roster.id()).orElseThrow();
        assertEquals(newOwner, plot.owner());
        assertTrue(plot.isMemberOrOwner(oldOwner)); // demoted to OFFICER, but still keeps plot bypass as a member
    }

    // ---- disband ------------------------------------------------------------------------------

    @Test
    void disbandRemovesTheGuildAndEveryMemberFromTheCache() throws Exception {
        long now = System.currentTimeMillis();
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        GuildRoster roster = create("Astra", owner, now).roster();
        guildService.addMember(roster.id(), member, GuildRole.MEMBER, now);

        guildService.disband(roster.id());

        assertFalse(guildService.rosterFor(owner).isPresent());
        assertFalse(guildService.rosterFor(member).isPresent());
        assertFalse(guildService.rosterById(roster.id()).isPresent());
    }

    @Test
    void disbandDeletesThePlotRegionAndFreesItsSlotForReuse() throws Exception {
        long now = System.currentTimeMillis();
        GuildRoster roster = create("Astra", UUID.randomUUID(), now).roster();
        int freedSlot = roster.record().plotSlotIndex();

        guildService.disband(roster.id());

        assertFalse(protectAreaService.find("guild-plot-" + roster.id()).isPresent());
        GuildRoster next = create("Nova", UUID.randomUUID(), now + 1).roster();
        assertEquals(freedSlot, next.record().plotSlotIndex()); // the freed slot is reused, not skipped
    }

    @Test
    void disbandFreesTheNameForReuse() throws Exception {
        long now = System.currentTimeMillis();
        GuildRoster roster = create("Astra", UUID.randomUUID(), now).roster();
        guildService.disband(roster.id());

        GuildService.CreateOutcome outcome = create("Astra", UUID.randomUUID(), now + 1);

        assertEquals(GuildService.CreateResult.CREATED, outcome.result());
    }

    @Test
    void loadPersistedStateRebuildsTheCacheFromTheDatabase() throws Exception {
        long now = System.currentTimeMillis();
        UUID owner = UUID.randomUUID();
        GuildRoster roster = create("Astra", owner, now).roster();

        // Simulate a restart: fresh service instances over the same database.
        GuildPlotWorldService reloadedPlotService = new GuildPlotWorldService(guildDao, protectAreaService);
        GuildService reloaded = new GuildService(guildDao, guildMemberDao, reloadedPlotService);
        protectAreaService.loadPersistedState();
        reloadedPlotService.loadPersistedState();
        reloaded.loadPersistedState();

        assertTrue(reloaded.rosterFor(owner).isPresent());
        assertEquals(roster.name(), reloaded.rosterFor(owner).orElseThrow().name());
    }

    @Test
    void listReturnsEveryGuildSortedByName() throws Exception {
        long now = System.currentTimeMillis();
        create("Zebra", UUID.randomUUID(), now);
        create("Astra", UUID.randomUUID(), now);

        var list = guildService.list();

        assertEquals(2, list.size());
        assertEquals("Astra", list.get(0).name());
        assertEquals("Zebra", list.get(1).name());
    }

    @Test
    void isValidNameRejectsStaffImpersonation() {
        assertFalse(GuildService.isValidName("[Owner]", 3, 24));
    }

    // ---- resetPlotWorld -----------------------------------------------------------------

    @Test
    void resetPlotWorldGivesEveryExistingGuildAFreshPlot() throws Exception {
        long now = System.currentTimeMillis();
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        GuildRoster rosterA = create("Astra", ownerA, now).roster();
        GuildRoster rosterB = create("Nova", ownerB, now).roster();
        int oldPlotAreaIdA = rosterA.record().plotAreaId();

        guildService.resetPlotWorld(PLOT_WORLD, GRID_SETTINGS, now + 1);

        GuildRoster reloadedA = guildService.rosterFor(ownerA).orElseThrow();
        GuildRoster reloadedB = guildService.rosterFor(ownerB).orElseThrow();
        assertNotNull(reloadedA.record().plotAreaId());
        assertNotNull(reloadedB.record().plotAreaId());
        assertTrue(reloadedA.record().plotAreaId() != oldPlotAreaIdA); // a genuinely new region, not the old one reused
        assertTrue(protectAreaService.find("guild-plot-" + rosterA.id()).isPresent());
    }

    @Test
    void resetPlotWorldDeletesEveryOldPlotRegion() throws Exception {
        long now = System.currentTimeMillis();
        GuildRoster roster = create("Astra", UUID.randomUUID(), now).roster();
        int oldAreaId = roster.record().plotAreaId();

        guildService.resetPlotWorld(PLOT_WORLD, GRID_SETTINGS, now + 1);

        // The old area id is gone - a fresh region (a new id) replaced it, not an update in place.
        assertTrue(protectAreaService.find("guild-plot-" + roster.id()).orElseThrow().id() != oldAreaId);
    }

    @Test
    void resetPlotWorldReSyncsNonOwnerMemberBypass() throws Exception {
        long now = System.currentTimeMillis();
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        GuildRoster roster = create("Astra", owner, now).roster();
        guildService.addMember(roster.id(), member, GuildRole.MEMBER, now);

        guildService.resetPlotWorld(PLOT_WORLD, GRID_SETTINGS, now + 1);

        AreaRegion freshPlot = protectAreaService.find("guild-plot-" + roster.id()).orElseThrow();
        assertTrue(freshPlot.isMemberOrOwner(member)); // still has bypass on the brand-new region
    }

    @Test
    void resetPlotWorldReinstatesTheBoundaryOutsideEveryPlot() throws Exception {
        long now = System.currentTimeMillis();
        create("Astra", UUID.randomUUID(), now);

        guildService.resetPlotWorld(PLOT_WORLD, GRID_SETTINGS, now + 1);

        assertTrue(protectAreaService.find("guild-plotworld-boundary").isPresent());
        // Well within the boundary's bounds for this fixture's small GRID_SETTINGS (plotSize
        // 16 x gridColumns 10 = 160 wide, plus an 8-block margin), but outside slot 0's own
        // 16-block footprint - i.e. the gap/unclaimed area, not any actual claimed plot.
        AreaRegion effective = protectAreaService.effectiveRegionAt(PLOT_WORLD, 150, 0, 150).orElseThrow();
        assertFalse(effective.effectiveFlag(AreaFlag.BUILD)); // still locked down outside any claimed plot
    }
}
