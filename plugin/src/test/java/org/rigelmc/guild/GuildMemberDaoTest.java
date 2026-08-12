package org.rigelmc.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class GuildMemberDaoTest {

    private HikariDataSource dataSource;
    private GuildDao guildDao;
    private GuildMemberDao guildMemberDao;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.guildDao = new GuildDao(dataSource);
        this.guildMemberDao = new GuildMemberDao(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void addThenFindForGuildReturnsTheMember() throws Exception {
        long now = System.currentTimeMillis();
        UUID owner = UUID.randomUUID();
        int guildId = guildDao.insert("Astra", owner, now);

        guildMemberDao.add(guildId, owner, GuildRole.OWNER, now);

        List<GuildMemberRecord> members = guildMemberDao.findForGuild(guildId);
        assertEquals(1, members.size());
        assertEquals(owner, members.get(0).memberUuid());
        assertEquals(GuildRole.OWNER, members.get(0).role());
    }

    @Test
    void updateRoleChangesTheStoredRole() throws Exception {
        long now = System.currentTimeMillis();
        UUID member = UUID.randomUUID();
        int guildId = guildDao.insert("Astra", UUID.randomUUID(), now);
        guildMemberDao.add(guildId, member, GuildRole.MEMBER, now);

        guildMemberDao.updateRole(guildId, member, GuildRole.OFFICER);

        assertEquals(GuildRole.OFFICER, guildMemberDao.findForGuild(guildId).stream()
                .filter(m -> m.memberUuid().equals(member)).findFirst().orElseThrow().role());
    }

    @Test
    void removeDeletesTheMembershipRow() throws Exception {
        long now = System.currentTimeMillis();
        UUID member = UUID.randomUUID();
        int guildId = guildDao.insert("Astra", UUID.randomUUID(), now);
        guildMemberDao.add(guildId, member, GuildRole.MEMBER, now);

        guildMemberDao.remove(guildId, member);

        assertTrue(guildMemberDao.findForGuild(guildId).isEmpty());
        assertFalse(guildMemberDao.findGuildIdForMember(member).isPresent());
    }

    @Test
    void removeAllForGuildClearsEveryMember() throws Exception {
        long now = System.currentTimeMillis();
        int guildId = guildDao.insert("Astra", UUID.randomUUID(), now);
        guildMemberDao.add(guildId, UUID.randomUUID(), GuildRole.OWNER, now);
        guildMemberDao.add(guildId, UUID.randomUUID(), GuildRole.MEMBER, now);

        guildMemberDao.removeAllForGuild(guildId);

        assertTrue(guildMemberDao.findForGuild(guildId).isEmpty());
    }

    @Test
    void findGuildIdForMemberResolvesTheOwningGuild() throws Exception {
        long now = System.currentTimeMillis();
        UUID member = UUID.randomUUID();
        int guildId = guildDao.insert("Astra", UUID.randomUUID(), now);
        guildMemberDao.add(guildId, member, GuildRole.MEMBER, now);

        assertEquals(guildId, guildMemberDao.findGuildIdForMember(member).orElseThrow());
    }

    @Test
    void aMemberUuidIsUniqueAcrossGuilds() throws Exception {
        long now = System.currentTimeMillis();
        UUID member = UUID.randomUUID();
        int firstGuild = guildDao.insert("Astra", UUID.randomUUID(), now);
        int secondGuild = guildDao.insert("Nova", UUID.randomUUID(), now);
        guildMemberDao.add(firstGuild, member, GuildRole.MEMBER, now);

        assertThrows(SQLException.class, () -> guildMemberDao.add(secondGuild, member, GuildRole.MEMBER, now));
    }

    @Test
    void findAllReturnsEveryMembershipAcrossGuilds() throws Exception {
        long now = System.currentTimeMillis();
        int firstGuild = guildDao.insert("Astra", UUID.randomUUID(), now);
        int secondGuild = guildDao.insert("Nova", UUID.randomUUID(), now);
        guildMemberDao.add(firstGuild, UUID.randomUUID(), GuildRole.OWNER, now);
        guildMemberDao.add(secondGuild, UUID.randomUUID(), GuildRole.OWNER, now);

        assertEquals(2, guildMemberDao.findAll().size());
    }
}
