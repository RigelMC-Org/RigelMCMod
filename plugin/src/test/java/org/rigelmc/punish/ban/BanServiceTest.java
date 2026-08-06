package org.rigelmc.punish.ban;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.audit.AuditLogDao;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.data.TestDatabase;
import org.rigelmc.data.dao.IpHistoryDao;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.identity.PlayerIdentity;

class BanServiceTest {

    private HikariDataSource dataSource;
    private BanService banService;
    private PlayerDao playerDao;
    private IpHistoryDao ipHistoryDao;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.playerDao = new PlayerDao(dataSource);
        this.ipHistoryDao = new IpHistoryDao(dataSource);
        AuditLogService auditLogService = new AuditLogService(new AuditLogDao(dataSource));
        this.banService = new BanService(new BanDao(dataSource), playerDao, ipHistoryDao, auditLogService);
    }

    @AfterEach
    void tearDown() {
        // Close Hikari's pooled connections before JUnit tries to delete the @TempDir -
        // on Windows, a still-open SQLite file handle makes that cleanup fail.
        dataSource.close();
    }

    @Test
    void quickBanIsActiveUntilItsExpiry() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        banService.banByName(uuid, "Griefer", "test", null, now, now + 1000);

        assertTrue(banService.isBanned(uuid, now));
        assertFalse(banService.isBanned(uuid, now + 2000)); // past expiry
    }

    @Test
    void unbanOnNeverBannedPlayerReturnsZeroInsteadOfThrowing() {
        // Direct regression test for the crash TFM's own /permban list had on an empty
        // result set (IndexOutOfBoundsException) - the defensive "0 means not banned,
        // not an error" contract must hold even with zero rows in the table.
        assertDoesNotThrow(() -> {
            int revoked = banService.unban("NeverBanned", null, false, null, System.currentTimeMillis());
            assertEquals(0, revoked);
        });
    }

    @Test
    void permbanByNameCascadesToEveryKnownIp() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        playerDao.upsertOnLogin(uuid, "Alt1", PlayerIdentity.JAVA, now);
        ipHistoryDao.recordSeen(uuid, "iphash-home", now);
        ipHistoryDao.recordSeen(uuid, "iphash-mobile", now);

        List<Ban> cascade = banService.permban("Alt1", null, "evasion", null, now);

        assertEquals(3, cascade.size()); // 1 name entry + 2 IP entries
        String caseId = cascade.get(0).caseId();
        assertTrue(cascade.stream().allMatch(b -> caseId.equals(b.caseId())));
        assertTrue(banService.isBanned(uuid, now));
        assertTrue(banService.isIpBanned("iphash-home", now));
        assertTrue(banService.isIpBanned("iphash-mobile", now));
    }

    @Test
    void permbanByIpCascadesToEveryKnownName() throws Exception {
        UUID uuidA = UUID.randomUUID();
        UUID uuidB = UUID.randomUUID();
        long now = System.currentTimeMillis();
        playerDao.upsertOnLogin(uuidA, "Housemate1", PlayerIdentity.JAVA, now);
        playerDao.upsertOnLogin(uuidB, "Housemate2", PlayerIdentity.JAVA, now);
        ipHistoryDao.recordSeen(uuidA, "iphash-shared", now);
        ipHistoryDao.recordSeen(uuidB, "iphash-shared", now);

        List<Ban> cascade = banService.permban("1.2.3.4", "iphash-shared", "shared IP ban", null, now);

        assertEquals(3, cascade.size()); // 1 IP entry + 2 name entries
        assertTrue(banService.isBanned(uuidA, now));
        assertTrue(banService.isBanned(uuidB, now));
        assertTrue(banService.isIpBanned("iphash-shared", now));
    }

    @Test
    void unbanWithoutCaseFlagOnlyLiftsTheTargetedEntry() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        playerDao.upsertOnLogin(uuid, "Alt2", PlayerIdentity.JAVA, now);
        ipHistoryDao.recordSeen(uuid, "iphash-only", now);
        banService.permban("Alt2", null, "evasion", null, now);

        int revoked = banService.unban("9.9.9.9", "iphash-only", false, null, now + 1);

        assertEquals(1, revoked);
        assertFalse(banService.isIpBanned("iphash-only", now + 1));
        assertTrue(banService.isBanned(uuid, now + 1)); // the name entry from the same cascade stays active
    }

    @Test
    void unbanWithCaseFlagLiftsTheWholeCascade() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        playerDao.upsertOnLogin(uuid, "Alt3", PlayerIdentity.JAVA, now);
        ipHistoryDao.recordSeen(uuid, "iphash-a", now);
        ipHistoryDao.recordSeen(uuid, "iphash-b", now);
        banService.permban("Alt3", null, "evasion", null, now);

        int revoked = banService.unban("Alt3", null, true, null, now + 1);

        assertEquals(3, revoked);
        assertFalse(banService.isBanned(uuid, now + 1));
        assertFalse(banService.isIpBanned("iphash-a", now + 1));
        assertFalse(banService.isIpBanned("iphash-b", now + 1));
    }
}
