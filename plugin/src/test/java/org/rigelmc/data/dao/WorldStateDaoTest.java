package org.rigelmc.data.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class WorldStateDaoTest {

    private HikariDataSource dataSource;
    private WorldStateDao dao;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.dao = new WorldStateDao(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void unknownWorldHasNoRecordedWipe() throws Exception {
        assertTrue(dao.findLastWipeAt("flatlands").isEmpty());
    }

    @Test
    void setThenGetRoundTrips() throws Exception {
        dao.setLastWipeAt("flatlands", 12345L);
        assertEquals(12345L, dao.findLastWipeAt("flatlands").orElseThrow());
    }

    @Test
    void settingAgainUpdatesRatherThanDuplicating() throws Exception {
        dao.setLastWipeAt("flatlands", 1L);
        dao.setLastWipeAt("flatlands", 2L);
        assertEquals(2L, dao.findLastWipeAt("flatlands").orElseThrow());
    }
}
