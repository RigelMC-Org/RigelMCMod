package org.rigelmc.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class EconomyDaoTest {

    private HikariDataSource dataSource;
    private EconomyDao economyDao;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.economyDao = new EconomyDao(dataSource);
    }

    @AfterEach
    void tearDown() {
        // Close Hikari's pooled connections before JUnit tries to delete the @TempDir -
        // on Windows, a still-open SQLite file handle makes that cleanup fail.
        dataSource.close();
    }

    @Test
    void balanceOfAPlayerWithNoRowIsZero() throws Exception {
        assertEquals(0L, economyDao.findBalance(UUID.randomUUID()));
    }

    @Test
    void creditingCreatesTheAccountRowAndAppliesTheDelta() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        long newBalance = economyDao.adjustBalance(uuid, 100, LedgerReason.ADMIN_GIVE, null, null, now);

        assertEquals(100L, newBalance);
        assertEquals(100L, economyDao.findBalance(uuid));
    }

    @Test
    void debitingBelowZeroThrowsAndWritesNothing() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        economyDao.adjustBalance(uuid, 50, LedgerReason.ADMIN_GIVE, null, null, now);

        InsufficientFundsException thrown = assertThrows(InsufficientFundsException.class,
                () -> economyDao.adjustBalance(uuid, -100, LedgerReason.ADMIN_TAKE, null, null, now));

        assertEquals(100L, thrown.requested());
        assertEquals(50L, thrown.available());
        assertEquals(50L, economyDao.findBalance(uuid)); // unchanged - the failed debit was rolled back
        assertTrue(economyDao.findLedgerFor(uuid, 10).size() == 1); // only the original credit, no failed-debit row
    }

    @Test
    void ledgerRecordsEveryChangeNewestFirst() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        economyDao.adjustBalance(uuid, 100, LedgerReason.ADMIN_GIVE, null, null, now);
        economyDao.adjustBalance(uuid, -30, LedgerReason.ADMIN_TAKE, null, null, now + 1);

        List<LedgerEntry> ledger = economyDao.findLedgerFor(uuid, 10);

        assertEquals(2, ledger.size());
        assertEquals(-30L, ledger.get(0).delta()); // newest first
        assertEquals(70L, ledger.get(0).balanceAfter());
        assertEquals(100L, ledger.get(1).delta());
    }

    @Test
    void topOrdersByBalanceDescending() throws Exception {
        UUID low = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        long now = System.currentTimeMillis();
        economyDao.adjustBalance(low, 10, LedgerReason.ADMIN_GIVE, null, null, now);
        economyDao.adjustBalance(high, 500, LedgerReason.ADMIN_GIVE, null, null, now);

        List<BalanceEntry> top = economyDao.top(10);

        assertEquals(high, top.get(0).uuid());
        assertEquals(500L, top.get(0).balance());
    }
}
