package org.rigelmc.protect.area;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class AreaDaoTest {

    private HikariDataSource dataSource;
    private AreaDao dao;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.dao = new AreaDao(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void insertThenFindRoundTrips() throws Exception {
        UUID actor = UUID.randomUUID();
        int id = dao.insert("Spawn", "world", "CUBOID", -10, 0, -10, 10, 20, 10, 0, actor, actor, 1000L);

        AreaRecord record = dao.findByNameLower("spawn").orElseThrow();
        assertEquals(id, record.id());
        assertEquals("Spawn", record.name());
        assertEquals("world", record.world());
        assertEquals(-10, record.minX());
        assertEquals(10, record.maxX());
        assertTrue(record.enabled());
        assertEquals(actor, record.owner());
    }

    @Test
    void nameLookupIsCaseInsensitive() throws Exception {
        dao.insert("Spawn", "world", "CUBOID", 0, 0, 0, 1, 1, 1, 0, null, null, 1000L);
        assertTrue(dao.findByNameLower("SPAWN".toLowerCase(java.util.Locale.ROOT)).isPresent());
    }

    @Test
    void updateBoundsChangesStoredBounds() throws Exception {
        int id = dao.insert("Region", "world", "CUBOID", 0, 0, 0, 1, 1, 1, 0, null, null, 1000L);
        dao.updateBounds(id, "world", 5, 5, 5, 10, 10, 10, null, 2000L);

        AreaRecord record = dao.findByNameLower("region").orElseThrow();
        assertEquals(5, record.minX());
        assertEquals(10, record.maxX());
    }

    @Test
    void renameChangesNameAndNameLower() throws Exception {
        int id = dao.insert("Old", "world", "CUBOID", 0, 0, 0, 1, 1, 1, 0, null, null, 1000L);
        dao.rename(id, "New", null, 2000L);

        assertTrue(dao.findByNameLower("old").isEmpty());
        assertEquals("New", dao.findByNameLower("new").orElseThrow().name());
    }

    @Test
    void deleteRemovesTheRegion() throws Exception {
        int id = dao.insert("Gone", "world", "CUBOID", 0, 0, 0, 1, 1, 1, 0, null, null, 1000L);
        dao.delete(id);
        assertTrue(dao.findByNameLower("gone").isEmpty());
    }

    @Test
    void deleteAllWipesEveryRegion() throws Exception {
        dao.insert("A", "world", "CUBOID", 0, 0, 0, 1, 1, 1, 0, null, null, 1000L);
        dao.insert("B", "world", "CUBOID", 5, 5, 5, 6, 6, 6, 0, null, null, 1000L);
        dao.deleteAll();
        assertEquals(0, dao.count());
    }

    @Test
    void findOverlappingMatchesAnIntersectingBox() throws Exception {
        dao.insert("Base", "world", "CUBOID", 0, 0, 0, 10, 10, 10, 0, null, null, 1000L);
        List<AreaRecord> overlapping = dao.findOverlapping("world", 5, 5, 5, 15, 15, 15, null);
        assertEquals(1, overlapping.size());
        assertEquals("Base", overlapping.get(0).name());
    }

    @Test
    void findOverlappingIgnoresANonIntersectingBox() throws Exception {
        dao.insert("Base", "world", "CUBOID", 0, 0, 0, 10, 10, 10, 0, null, null, 1000L);
        List<AreaRecord> overlapping = dao.findOverlapping("world", 100, 100, 100, 110, 110, 110, null);
        assertTrue(overlapping.isEmpty());
    }

    @Test
    void findOverlappingIgnoresADifferentWorld() throws Exception {
        dao.insert("Base", "nether", "CUBOID", 0, 0, 0, 10, 10, 10, 0, null, null, 1000L);
        assertTrue(dao.findOverlapping("world", 0, 0, 0, 10, 10, 10, null).isEmpty());
    }

    @Test
    void findOverlappingExcludesTheGivenId() throws Exception {
        int id = dao.insert("Self", "world", "CUBOID", 0, 0, 0, 10, 10, 10, 0, null, null, 1000L);
        assertTrue(dao.findOverlapping("world", 0, 0, 0, 10, 10, 10, id).isEmpty());
    }

    @Test
    void findOverlappingIgnoresADisabledRegion() throws Exception {
        int id = dao.insert("Disabled", "world", "CUBOID", 0, 0, 0, 10, 10, 10, 0, null, null, 1000L);
        dao.setEnabled(id, false, null, 2000L);
        assertTrue(dao.findOverlapping("world", 0, 0, 0, 10, 10, 10, null).isEmpty());
    }

    @Test
    void setPriorityAndSetOwnerRoundTrip() throws Exception {
        UUID newOwner = UUID.randomUUID();
        int id = dao.insert("Region", "world", "CUBOID", 0, 0, 0, 1, 1, 1, 0, null, null, 1000L);
        dao.setPriority(id, 7, null, 2000L);
        dao.setOwner(id, newOwner, null, 2000L);

        AreaRecord record = dao.findByNameLower("region").orElseThrow();
        assertEquals(7, record.priority());
        assertEquals(newOwner, record.owner());
    }

    @Test
    void unknownNameIsEmpty() throws Exception {
        Optional<AreaRecord> result = dao.findByNameLower("nonexistent");
        assertFalse(result.isPresent());
    }
}
