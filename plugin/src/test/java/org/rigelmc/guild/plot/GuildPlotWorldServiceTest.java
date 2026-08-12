package org.rigelmc.guild.plot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;
import org.rigelmc.guild.GuildDao;
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
 * Covers the whole-plot-world lockdown boundary and its nesting interaction with
 * individual guild plots - see {@link GuildPlotWorldService}'s own javadoc. Real {@link
 * ProtectAreaService} against a real temp-file SQLite database, no MockBukkit - same
 * "real service, Proxy-faked Plugin" precedent {@code ProtectAreaServiceTest}/{@code
 * GuildServiceTest} already established (needed here only because {@link
 * ProtectAreaService} itself requires a {@link PermissionGate}).
 */
class GuildPlotWorldServiceTest {

    private static final String WORLD = "guildplots";
    private static final GuildPlotWorldService.PlotGridSettings SETTINGS =
            new GuildPlotWorldService.PlotGridSettings(300, 16, 10, -20, 150);

    private HikariDataSource dataSource;
    private GuildDao guildDao;
    private ProtectAreaService protectAreaService;
    private GuildPlotWorldService guildPlotWorldService;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.guildDao = new GuildDao(dataSource);

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
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void boundaryDeniesEveryFlagOutsideAnyPlot() throws Exception {
        guildPlotWorldService.ensureBoundaryProtectionExists(WORLD, SETTINGS, 1000L);

        // Deep in the gap between grid slots - never covered by any plot.
        AreaRegion effective = protectAreaService.effectiveRegionAt(WORLD, 305, 0, 0).orElseThrow();
        assertFalse(effective.effectiveFlag(AreaFlag.BUILD));
        assertFalse(effective.effectiveFlag(AreaFlag.EXPLOSIONS));
        assertFalse(effective.effectiveFlag(AreaFlag.MOB_SPAWN));
        assertFalse(effective.effectiveFlag(AreaFlag.INTERACT));
        assertFalse(effective.effectiveFlag(AreaFlag.PVP));
        assertFalse(effective.effectiveFlag(AreaFlag.ITEM_PICKUP));
        assertTrue(effective.effectiveFlag(AreaFlag.ENTRY)); // walking around is still allowed
    }

    @Test
    void boundaryAlsoCoversAnUnclaimedGridSlot() throws Exception {
        guildPlotWorldService.ensureBoundaryProtectionExists(WORLD, SETTINGS, 1000L);

        // Slot 0's own footprint - nobody has claimed it, so only the boundary applies.
        AreaRegion effective = protectAreaService.effectiveRegionAt(WORLD, 10, 0, 10).orElseThrow();
        assertFalse(effective.effectiveFlag(AreaFlag.BUILD));
    }

    @Test
    void aClaimedPlotOverridesTheBoundaryWithinItsOwnFootprint() throws Exception {
        guildPlotWorldService.ensureBoundaryProtectionExists(WORLD, SETTINGS, 1000L);
        UUID owner = UUID.randomUUID();
        GuildPlotWorldService.PlotAssignmentOutcome plot =
                guildPlotWorldService.assignPlotForGuild(1, owner, WORLD, SETTINGS, 2000L);
        assertTrue(plot.assigned());

        AreaRegion effective = protectAreaService.effectiveRegionAt(WORLD, 10, 0, 10).orElseThrow();
        assertEquals("guild-plot-1", effective.name()); // the plot wins the nesting contest, not the boundary
        assertFalse(effective.effectiveFlag(AreaFlag.BUILD)); // still denied by default...
        assertTrue(effective.isMemberOrOwner(owner)); // ...but the owner bypasses via area ownership
    }

    @Test
    void outsideThePlotFootprintTheBoundaryStillGoverns() throws Exception {
        guildPlotWorldService.ensureBoundaryProtectionExists(WORLD, SETTINGS, 1000L);
        UUID owner = UUID.randomUUID();
        guildPlotWorldService.assignPlotForGuild(1, owner, WORLD, SETTINGS, 2000L);

        // Just past slot 0's 300-block footprint - into the gap, still the owner's "neighborhood" but not their plot.
        AreaRegion effective = protectAreaService.effectiveRegionAt(WORLD, 305, 0, 10).orElseThrow();
        assertEquals("guild-plotworld-boundary", effective.name());
        assertFalse(effective.isMemberOrOwner(owner)); // the plot owner has no bypass here - not their plot
    }

    @Test
    void ensureBoundaryProtectionExistsIsIdempotent() throws Exception {
        guildPlotWorldService.ensureBoundaryProtectionExists(WORLD, SETTINGS, 1000L);
        Optional<AreaRegion> first = protectAreaService.find("guild-plotworld-boundary");
        guildPlotWorldService.ensureBoundaryProtectionExists(WORLD, SETTINGS, 2000L);
        Optional<AreaRegion> second = protectAreaService.find("guild-plotworld-boundary");

        assertTrue(first.isPresent());
        assertEquals(first.get().id(), second.orElseThrow().id()); // same row, not a duplicate
    }

    @Test
    void ensureBoundaryProtectionExistsResizesWhenGridSettingsChange() throws Exception {
        guildPlotWorldService.ensureBoundaryProtectionExists(WORLD, SETTINGS, 1000L);
        int originalMaxX = protectAreaService.find("guild-plotworld-boundary").orElseThrow().record().maxX();

        GuildPlotWorldService.PlotGridSettings biggerGrid =
                new GuildPlotWorldService.PlotGridSettings(500, 16, 20, -20, 150);
        guildPlotWorldService.ensureBoundaryProtectionExists(WORLD, biggerGrid, 2000L);
        int resizedMaxX = protectAreaService.find("guild-plotworld-boundary").orElseThrow().record().maxX();

        assertTrue(resizedMaxX > originalMaxX);
    }

    @Test
    void resetAllDeletesEveryPlotAndTheBoundary() throws Exception {
        guildPlotWorldService.ensureBoundaryProtectionExists(WORLD, SETTINGS, 1000L);
        long now = System.currentTimeMillis();
        int guildId = guildDao.insert("Astra", UUID.randomUUID(), now);
        GuildPlotWorldService.PlotAssignmentOutcome plot =
                guildPlotWorldService.assignPlotForGuild(guildId, UUID.randomUUID(), WORLD, SETTINGS, now);
        assertTrue(plot.assigned());

        guildPlotWorldService.resetAll();

        assertFalse(protectAreaService.find("guild-plot-" + guildId).isPresent());
        assertFalse(protectAreaService.find("guild-plotworld-boundary").isPresent());
        // The freed slot is reusable immediately - confirms occupiedSlots was cleared too.
        GuildPlotWorldService.PlotAssignmentOutcome reassigned =
                guildPlotWorldService.assignPlotForGuild(guildId, UUID.randomUUID(), WORLD, SETTINGS, now + 1);
        assertEquals(plot.plotSlotIndex(), reassigned.plotSlotIndex());
    }
}
