package org.rigelmc.protect.area;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class AreaFlagDaoTest {

    private HikariDataSource dataSource;
    private AreaFlagDao dao;
    private int areaId;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.dao = new AreaFlagDao(dataSource);
        AreaDao areaDao = new AreaDao(dataSource);
        this.areaId = areaDao.insert("Region", "world", "CUBOID", 0, 0, 0, 1, 1, 1, 0, null, null, 1000L);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void newRegionHasNoOverrides() throws Exception {
        assertTrue(dao.findForArea(areaId).isEmpty());
    }

    @Test
    void setOverrideThenFindRoundTrips() throws Exception {
        dao.setOverride(areaId, AreaFlag.PVP, false);
        assertEquals(Map.of(AreaFlag.PVP, false), dao.findForArea(areaId));
    }

    @Test
    void setOverrideAgainUpdatesRatherThanDuplicating() throws Exception {
        dao.setOverride(areaId, AreaFlag.PVP, false);
        dao.setOverride(areaId, AreaFlag.PVP, true);
        assertEquals(Map.of(AreaFlag.PVP, true), dao.findForArea(areaId));
    }

    @Test
    void clearOverrideRemovesIt() throws Exception {
        dao.setOverride(areaId, AreaFlag.BUILD, true);
        dao.clearOverride(areaId, AreaFlag.BUILD);
        assertTrue(dao.findForArea(areaId).isEmpty());
    }

    @Test
    void multipleFlagsCoexistIndependently() throws Exception {
        dao.setOverride(areaId, AreaFlag.PVP, false);
        dao.setOverride(areaId, AreaFlag.ENTRY, false);
        Map<AreaFlag, Boolean> overrides = dao.findForArea(areaId);
        assertEquals(2, overrides.size());
        assertEquals(false, overrides.get(AreaFlag.PVP));
        assertEquals(false, overrides.get(AreaFlag.ENTRY));
    }
}
