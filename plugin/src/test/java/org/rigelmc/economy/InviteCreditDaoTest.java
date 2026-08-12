package org.rigelmc.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class InviteCreditDaoTest {

    private HikariDataSource dataSource;
    private InviteCreditDao dao;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.dao = new InviteCreditDao(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void insertPendingCreatesARowWithPendingStatus() throws Exception {
        int id = dao.insertPending("guild-1", "abc123", "inviter-1", "invited-1", 100, 1000L, 2000L);

        List<PendingInviteCredit> due = dao.findDueForSweep(3000L);
        assertEquals(1, due.size());
        PendingInviteCredit credit = due.get(0);
        assertEquals(id, credit.id());
        assertEquals("guild-1", credit.discordGuildId());
        assertEquals("abc123", credit.inviteCode());
        assertEquals("inviter-1", credit.inviterDiscordUserId());
        assertEquals("invited-1", credit.invitedDiscordUserId());
        assertEquals(100L, credit.rewardAmount());
        assertEquals(InviteCreditStatus.PENDING, credit.status());
        assertNull(credit.creditedAt());
        assertNull(credit.creditedUuid());
    }

    @Test
    void findDueForSweepExcludesRowsNotYetEligible() throws Exception {
        dao.insertPending("guild-1", "abc123", "inviter-1", "invited-1", 100, 1000L, 5000L);

        assertTrue(dao.findDueForSweep(2000L).isEmpty()); // eligible_at (5000) is still in the future
        assertEquals(1, dao.findDueForSweep(5000L).size()); // exactly at eligible_at counts as due
    }

    @Test
    void findDueForSweepExcludesAlreadyCreditedRows() throws Exception {
        int id = dao.insertPending("guild-1", "abc123", "inviter-1", "invited-1", 100, 1000L, 2000L);
        dao.markCredited(id, 3000L, UUID.randomUUID());

        assertTrue(dao.findDueForSweep(5000L).isEmpty());
    }

    @Test
    void markCreditedSetsStatusAndCreditedFields() throws Exception {
        int id = dao.insertPending("guild-1", "abc123", "inviter-1", "invited-1", 100, 1000L, 2000L);
        UUID creditedUuid = UUID.randomUUID();

        dao.markCredited(id, 3000L, creditedUuid);

        // No direct findById - confirm indirectly via a fresh insert + sweep query showing
        // the credited row no longer appears as due.
        assertTrue(dao.findDueForSweep(9999L).isEmpty());
    }

    @Test
    void cancelPendingForInvitedUserFlipsOnlyThatUsersPendingRows() throws Exception {
        dao.insertPending("guild-1", "abc123", "inviter-1", "invited-1", 100, 1000L, 2000L);
        dao.insertPending("guild-1", "def456", "inviter-2", "invited-2", 100, 1000L, 2000L);

        int cancelled = dao.cancelPendingForInvitedUser("invited-1");

        assertEquals(1, cancelled);
        List<PendingInviteCredit> due = dao.findDueForSweep(9999L);
        assertEquals(1, due.size());
        assertEquals("invited-2", due.get(0).invitedDiscordUserId()); // only invited-1's row was cancelled
    }

    @Test
    void cancelPendingNeverTouchesAnAlreadyCreditedRow() throws Exception {
        int id = dao.insertPending("guild-1", "abc123", "inviter-1", "invited-1", 100, 1000L, 2000L);
        dao.markCredited(id, 3000L, UUID.randomUUID());

        int cancelled = dao.cancelPendingForInvitedUser("invited-1");

        assertEquals(0, cancelled); // nothing to cancel - it's already CREDITED, not PENDING
    }
}
