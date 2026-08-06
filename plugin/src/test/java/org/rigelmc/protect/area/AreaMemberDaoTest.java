package org.rigelmc.protect.area;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class AreaMemberDaoTest {

    private HikariDataSource dataSource;
    private AreaDao areaDao;
    private AreaMemberDao memberDao;
    private int areaId;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.areaDao = new AreaDao(dataSource);
        this.memberDao = new AreaMemberDao(dataSource);
        this.areaId = areaDao.insert("Region", "world", "CUBOID", 0, 0, 0, 1, 1, 1, 0, null, null, 1000L);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void newRegionHasNoMembers() throws Exception {
        assertTrue(memberDao.findForArea(areaId).isEmpty());
    }

    @Test
    void addThenFindRoundTrips() throws Exception {
        UUID member = UUID.randomUUID();
        memberDao.add(areaId, member, null, 1000L);
        assertEquals(Set.of(member), memberDao.findForArea(areaId));
    }

    @Test
    void addIsIdempotent() throws Exception {
        UUID member = UUID.randomUUID();
        memberDao.add(areaId, member, null, 1000L);
        memberDao.add(areaId, member, null, 2000L); // must not throw a duplicate-key error
        assertEquals(1, memberDao.findForArea(areaId).size());
    }

    @Test
    void removeDeletesTheMember() throws Exception {
        UUID member = UUID.randomUUID();
        memberDao.add(areaId, member, null, 1000L);
        memberDao.remove(areaId, member);
        assertTrue(memberDao.findForArea(areaId).isEmpty());
    }

    @Test
    void membersAreScopedPerArea() throws Exception {
        int otherAreaId = areaDao.insert("Other", "world", "CUBOID", 5, 5, 5, 6, 6, 6, 0, null, null, 1000L);
        UUID member = UUID.randomUUID();
        memberDao.add(areaId, member, null, 1000L);
        assertTrue(memberDao.findForArea(otherAreaId).isEmpty());
    }
}
