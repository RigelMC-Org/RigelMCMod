package org.rigelmc.vote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class VoteRecordDaoTest {

    private HikariDataSource dataSource;
    private VoteRecordDao dao;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.dao = new VoteRecordDao(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void findReturnsEmptyForAnUnknownPlayer() throws Exception {
        assertTrue(dao.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void upsertInsertsANewRecord() throws Exception {
        UUID uuid = UUID.randomUUID();
        dao.upsert(new VoteRecord(uuid, 1, 1, 1000L, 1000L));

        Optional<VoteRecord> found = dao.find(uuid);
        assertTrue(found.isPresent());
        VoteRecord record = found.get();
        assertEquals(uuid, record.uuid());
        assertEquals(1, record.totalVotes());
        assertEquals(1, record.currentStreak());
        assertEquals(1000L, record.lastVoteAt());
        assertEquals(1000L, record.updatedAt());
    }

    @Test
    void upsertOverwritesAnExistingRecordRatherThanDuplicating() throws Exception {
        UUID uuid = UUID.randomUUID();
        dao.upsert(new VoteRecord(uuid, 1, 1, 1000L, 1000L));
        dao.upsert(new VoteRecord(uuid, 2, 2, 2000L, 2000L));

        Optional<VoteRecord> found = dao.find(uuid);
        assertTrue(found.isPresent());
        assertEquals(2, found.get().totalVotes());
        assertEquals(2, found.get().currentStreak());
        assertEquals(2000L, found.get().lastVoteAt());
    }

    @Test
    void upsertPersistsANullLastVoteAt() throws Exception {
        UUID uuid = UUID.randomUUID();
        dao.upsert(new VoteRecord(uuid, 0, 0, null, 1000L));

        Optional<VoteRecord> found = dao.find(uuid);
        assertTrue(found.isPresent());
        assertNull(found.get().lastVoteAt());
    }
}
