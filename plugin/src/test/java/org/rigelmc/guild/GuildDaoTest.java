package org.rigelmc.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class GuildDaoTest {

    private HikariDataSource dataSource;
    private GuildDao guildDao;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.guildDao = new GuildDao(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void insertReturnsAGeneratedIdAndPersistsTheRow() throws Exception {
        UUID owner = UUID.randomUUID();
        long now = System.currentTimeMillis();

        int id = guildDao.insert("Astra", owner, now);

        GuildRecord record = guildDao.findById(id).orElseThrow();
        assertEquals("Astra", record.name());
        assertEquals("astra", record.nameLower());
        assertEquals(owner, record.ownerUuid());
        assertNull(record.plotAreaId());
        assertNull(record.plotSlotIndex());
    }

    @Test
    void nameLowerIsUniqueAcrossCase() throws Exception {
        long now = System.currentTimeMillis();
        guildDao.insert("Astra", UUID.randomUUID(), now);

        assertThrows(SQLException.class, () -> guildDao.insert("astra", UUID.randomUUID(), now));
    }

    @Test
    void findByNameLowerIsCaseInsensitive() throws Exception {
        long now = System.currentTimeMillis();
        guildDao.insert("Astra", UUID.randomUUID(), now);

        assertTrue(guildDao.findByNameLower("astra").isPresent());
    }

    @Test
    void setOwnerUpdatesTheOwnerUuid() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        long now = System.currentTimeMillis();
        int id = guildDao.insert("Astra", owner, now);

        guildDao.setOwner(id, newOwner, now + 1);

        assertEquals(newOwner, guildDao.findById(id).orElseThrow().ownerUuid());
    }

    @Test
    void setPlotThenClearPlotRoundTrips() throws Exception {
        long now = System.currentTimeMillis();
        int id = guildDao.insert("Astra", UUID.randomUUID(), now);

        guildDao.setPlot(id, 7, 3, now + 1);
        GuildRecord withPlot = guildDao.findById(id).orElseThrow();
        assertEquals(7, withPlot.plotAreaId());
        assertEquals(3, withPlot.plotSlotIndex());

        guildDao.clearPlot(id, now + 2);
        GuildRecord cleared = guildDao.findById(id).orElseThrow();
        assertNull(cleared.plotAreaId());
        assertNull(cleared.plotSlotIndex());
    }

    @Test
    void deleteRemovesTheRow() throws Exception {
        long now = System.currentTimeMillis();
        int id = guildDao.insert("Astra", UUID.randomUUID(), now);

        guildDao.delete(id);

        assertFalse(guildDao.findById(id).isPresent());
    }

    @Test
    void findAllOrdersByNameLower() throws Exception {
        long now = System.currentTimeMillis();
        guildDao.insert("Zebra", UUID.randomUUID(), now);
        guildDao.insert("Astra", UUID.randomUUID(), now);

        List<GuildRecord> all = guildDao.findAll();

        assertEquals(2, all.size());
        assertEquals("Astra", all.get(0).name());
        assertEquals("Zebra", all.get(1).name());
    }
}
