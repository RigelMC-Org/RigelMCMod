package org.rigelmc.protect.area;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.RankRepository;
import org.rigelmc.rank.RankService;

/**
 * Service-level tests against the real DAOs (a real temp-file SQLite database, no
 * MockBukkit - see {@code data.TestDatabase}). {@link PermissionGate} is constructed for
 * real (it's {@code final}, so it can't be faked/subclassed) but with a {@link Proxy}-based
 * no-op {@link Plugin} in place of a live one - safe here because this test only ever calls
 * {@link PermissionGate#hasAtLeastCached}, which reads a plain in-memory map and never
 * touches {@code plugin} at all; the only methods that do ({@code applyRank}/{@code
 * registerKnownPermissions}) are never called, so the proxy's "throw on any invocation"
 * body never actually runs.
 */
class ProtectAreaServiceTest {

    private HikariDataSource dataSource;
    private AreaMemberDao memberDao;
    private AreaFlagDao flagDao;
    private ProtectAreaService service;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        AreaDao areaDao = new AreaDao(dataSource);
        this.memberDao = new AreaMemberDao(dataSource);
        this.flagDao = new AreaFlagDao(dataSource);

        RankService rankService = new RankService(new RankRepository(dataSource), new org.rigelmc.data.dao.PlayerDao(dataSource));
        rankService.initialize();
        Plugin fakePlugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(
                            "Unexpected call to Plugin#" + method.getName() + " - this test never needs a live plugin");
                });
        PermissionGate permissionGate = new PermissionGate(fakePlugin, rankService);

        this.service = new ProtectAreaService(areaDao, memberDao, flagDao, permissionGate);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void createThenFindRoundTrips() throws Exception {
        ProtectAreaService.CreateOutcome outcome =
                service.create("Spawn", "world", 0, 0, 0, 10, 10, 10, false, null, 1000L);
        assertEquals(ProtectAreaService.CreateResult.OK, outcome.result());
        assertTrue(service.find("spawn").isPresent());
        assertEquals("Spawn", service.find("SPAWN").orElseThrow().name());
    }

    @Test
    void createRejectsADuplicateName() throws Exception {
        service.create("Spawn", "world", 0, 0, 0, 1, 1, 1, false, null, 1000L);
        ProtectAreaService.CreateOutcome outcome =
                service.create("Spawn", "world", 50, 50, 50, 51, 51, 51, false, null, 1000L);
        assertEquals(ProtectAreaService.CreateResult.DUPLICATE_NAME, outcome.result());
    }

    @Test
    void createRejectsAnOverlapWithoutNest() throws Exception {
        service.create("First", "world", 0, 0, 0, 10, 10, 10, false, null, 1000L);
        ProtectAreaService.CreateOutcome outcome =
                service.create("Second", "world", 5, 5, 5, 15, 15, 15, false, null, 1000L);
        assertEquals(ProtectAreaService.CreateResult.OVERLAP, outcome.result());
        assertEquals(1, outcome.conflicts().size());
        assertEquals("First", outcome.conflicts().get(0).name());
        assertTrue(service.find("second").isEmpty()); // rejected - nothing was created
    }

    @Test
    void createAllowsAnOverlapWithNestAndAssignsAHigherPriority() throws Exception {
        service.create("Outer", "world", 0, 0, 0, 100, 100, 100, false, null, 1000L);
        ProtectAreaService.CreateOutcome outcome =
                service.create("Inner", "world", 10, 10, 10, 20, 20, 20, true, null, 1000L);
        assertEquals(ProtectAreaService.CreateResult.OK, outcome.result());
        assertTrue(outcome.region().priority() > service.find("outer").orElseThrow().priority());
    }

    @Test
    void updateRejectsAnOverlapWithAnotherRegion() throws Exception {
        service.create("A", "world", 0, 0, 0, 10, 10, 10, false, null, 1000L);
        service.create("B", "world", 50, 50, 50, 60, 60, 60, false, null, 1000L);
        AreaRegion regionB = service.find("b").orElseThrow();

        ProtectAreaService.UpdateOutcome outcome =
                service.update(regionB, "world", 5, 5, 5, 15, 15, 15, false, null, 2000L);
        assertEquals(ProtectAreaService.UpdateResult.OVERLAP, outcome.result());
        // B's bounds must be unchanged after a rejected update.
        assertEquals(50, service.find("b").orElseThrow().record().minX());
    }

    @Test
    void updateDoesNotConsiderItselfAnOverlap() throws Exception {
        service.create("A", "world", 0, 0, 0, 10, 10, 10, false, null, 1000L);
        AreaRegion regionA = service.find("a").orElseThrow();

        // Same bounds, updated "again" - must not be rejected as overlapping itself.
        ProtectAreaService.UpdateOutcome outcome =
                service.update(regionA, "world", 0, 0, 0, 10, 10, 10, false, null, 2000L);
        assertEquals(ProtectAreaService.UpdateResult.OK, outcome.result());
    }

    @Test
    void deleteRemovesFromCache() throws Exception {
        service.create("Gone", "world", 0, 0, 0, 1, 1, 1, false, null, 1000L);
        AreaRegion region = service.find("gone").orElseThrow();
        service.delete(region);
        assertTrue(service.find("gone").isEmpty());
    }

    /**
     * {@code rigel_protect_areas} has no {@code ON DELETE CASCADE} - confirms {@link
     * ProtectAreaService#delete} itself cleans up the member/flag rows a naive {@code
     * areaDao.delete} alone would silently orphan. See that method's javadoc.
     */
    @Test
    void deleteCascadesToMemberAndFlagRows() throws Exception {
        service.create("Gone", "world", 0, 0, 0, 1, 1, 1, false, null, 1000L);
        AreaRegion region = service.find("gone").orElseThrow();
        UUID member = UUID.randomUUID();
        service.addMember(region, member, null, 1000L);
        service.setFlag(region, AreaFlag.PVP, true);

        service.delete(service.find("gone").orElseThrow());

        assertTrue(memberDao.findForArea(region.id()).isEmpty());
        assertTrue(flagDao.findForArea(region.id()).isEmpty());
    }

    @Test
    void clearAllRemovesEveryRegion() throws Exception {
        service.create("A", "world", 0, 0, 0, 1, 1, 1, false, null, 1000L);
        service.create("B", "world", 5, 5, 5, 6, 6, 6, false, null, 1000L);
        service.clearAll();
        assertTrue(service.list().isEmpty());
    }

    @Test
    void clearAllCascadesToMemberAndFlagRows() throws Exception {
        service.create("A", "world", 0, 0, 0, 1, 1, 1, false, null, 1000L);
        AreaRegion region = service.find("a").orElseThrow();
        UUID member = UUID.randomUUID();
        service.addMember(region, member, null, 1000L);
        service.setFlag(region, AreaFlag.PVP, true);

        service.clearAll();

        assertTrue(memberDao.findForArea(region.id()).isEmpty());
        assertTrue(flagDao.findForArea(region.id()).isEmpty());
    }

    @Test
    void renameUpdatesTheCacheKey() throws Exception {
        service.create("Old", "world", 0, 0, 0, 1, 1, 1, false, null, 1000L);
        AreaRegion region = service.find("old").orElseThrow();
        service.rename(region, "New", null, 2000L);
        assertTrue(service.find("old").isEmpty());
        assertTrue(service.find("new").isPresent());
    }

    @Test
    void effectiveRegionAtReturnsEmptyOutsideAnyRegion() throws Exception {
        service.create("Region", "world", 0, 0, 0, 10, 10, 10, false, null, 1000L);
        assertTrue(service.effectiveRegionAt("world", 1000, 1000, 1000).isEmpty());
    }

    @Test
    void effectiveRegionAtResolvesOverlapByPriority() throws Exception {
        service.create("Outer", "world", 0, 0, 0, 100, 100, 100, false, null, 1000L);
        service.create("Inner", "world", 10, 10, 10, 20, 20, 20, true, null, 1000L);

        AreaRegion effective = service.effectiveRegionAt("world", 15, 15, 15).orElseThrow();
        assertEquals("Inner", effective.name());
    }

    @Test
    void effectiveRegionAtIgnoresADisabledRegion() throws Exception {
        service.create("Region", "world", 0, 0, 0, 10, 10, 10, false, null, 1000L);
        AreaRegion region = service.find("region").orElseThrow();
        service.setEnabled(region, false, null, 2000L);
        assertTrue(service.effectiveRegionAt("world", 5, 5, 5).isEmpty());
    }

    @Test
    void setFlagOverridesThenClearRevertsToDefault() throws Exception {
        service.create("Region", "world", 0, 0, 0, 1, 1, 1, false, null, 1000L);
        AreaRegion region = service.find("region").orElseThrow();

        boolean flipped = !AreaFlag.PVP.defaultValue();
        service.setFlag(region, AreaFlag.PVP, flipped);
        assertEquals(flipped, service.find("region").orElseThrow().effectiveFlag(AreaFlag.PVP));

        service.setFlag(region, AreaFlag.PVP, null);
        assertEquals(AreaFlag.PVP.defaultValue(), service.find("region").orElseThrow().effectiveFlag(AreaFlag.PVP));
    }

    @Test
    void addThenRemoveMemberRoundTrips() throws Exception {
        service.create("Region", "world", 0, 0, 0, 1, 1, 1, false, null, 1000L);
        AreaRegion region = service.find("region").orElseThrow();
        UUID member = UUID.randomUUID();

        service.addMember(region, member, null, 1000L);
        assertTrue(service.find("region").orElseThrow().isMemberOrOwner(member));

        service.removeMember(region, member);
        assertFalse(service.find("region").orElseThrow().isMemberOrOwner(member));
    }

    @Test
    void isBypassingIsTrueForAMember() throws Exception {
        service.create("Region", "world", 0, 0, 0, 1, 1, 1, false, null, 1000L);
        AreaRegion region = service.find("region").orElseThrow();
        UUID member = UUID.randomUUID();
        service.addMember(region, member, null, 1000L);

        assertTrue(service.isBypassing(fakePlayer(member), service.find("region").orElseThrow()));
    }

    @Test
    void isBypassingIsFalseForAnUnrelatedNonStaffPlayer() throws Exception {
        service.create("Region", "world", 0, 0, 0, 1, 1, 1, false, null, 1000L);
        AreaRegion region = service.find("region").orElseThrow();

        // Not a member/owner, and PermissionGate's rank cache is empty (nothing ever
        // called applyRank in this test) - hasAtLeastCached degrades to false without
        // touching the fake Plugin at all, see this class's javadoc.
        assertFalse(service.isBypassing(fakePlayer(UUID.randomUUID()), region));
    }

    /** Only {@code getUniqueId()} is ever called on this by {@link ProtectAreaService#isBypassing} - see this class's javadoc. */
    private static Player fakePlayer(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return uuid;
                    }
                    throw new UnsupportedOperationException(
                            "Unexpected call to Player#" + method.getName() + " - this test only needs getUniqueId()");
                });
    }

    @Test
    void findOverlappingReflectsTheLiveCacheNotJustTheDatabase() throws Exception {
        service.create("A", "world", 0, 0, 0, 10, 10, 10, false, null, 1000L);
        List<AreaRegion> overlapping = service.findOverlapping("world", 5, 5, 5, 15, 15, 15, null);
        assertEquals(1, overlapping.size());
    }
}
