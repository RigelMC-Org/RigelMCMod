package org.rigelmc.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class EconomyServiceTest {

    private HikariDataSource dataSource;
    private EconomyService economyService;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.economyService = new EconomyService(new EconomyDao(dataSource));
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void creditIncreasesBalance() throws Exception {
        UUID uuid = UUID.randomUUID();
        economyService.credit(uuid, 100, LedgerReason.DISCORD_INVITE, "invite-code", null);
        assertEquals(100L, economyService.balanceOf(uuid));
    }

    @Test
    void debitBeyondBalanceThrows() throws Exception {
        UUID uuid = UUID.randomUUID();
        economyService.credit(uuid, 50, LedgerReason.ADMIN_GIVE, null, null);
        assertThrows(InsufficientFundsException.class,
                () -> economyService.debit(uuid, 100, LedgerReason.PAY_SEND, null, uuid));
        assertEquals(50L, economyService.balanceOf(uuid)); // unchanged
    }

    @Test
    void setBalanceOverwritesRegardlessOfCurrentValue() throws Exception {
        UUID uuid = UUID.randomUUID();
        economyService.credit(uuid, 999, LedgerReason.ADMIN_GIVE, null, null);
        economyService.setBalance(uuid, 42, null);
        assertEquals(42L, economyService.balanceOf(uuid));
    }

    @Test
    void setBalanceRejectsNegative() throws Exception {
        UUID uuid = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> economyService.setBalance(uuid, -1, null));
    }

    @Test
    void transferMovesFundsBetweenTwoAccounts() throws Exception {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        economyService.credit(from, 100, LedgerReason.ADMIN_GIVE, null, null);

        boolean success = economyService.transfer(from, to, 40);

        assertTrue(success);
        assertEquals(60L, economyService.balanceOf(from));
        assertEquals(40L, economyService.balanceOf(to));
    }

    @Test
    void transferWithInsufficientFundsMovesNothing() throws Exception {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        economyService.credit(from, 10, LedgerReason.ADMIN_GIVE, null, null);

        boolean success = economyService.transfer(from, to, 100);

        assertFalse(success);
        assertEquals(10L, economyService.balanceOf(from)); // unchanged
        assertEquals(0L, economyService.balanceOf(to)); // never credited
    }
}
