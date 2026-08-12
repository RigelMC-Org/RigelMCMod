package org.rigelmc.guild.plot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;
import org.rigelmc.economy.EconomyDao;
import org.rigelmc.economy.EconomyService;
import org.rigelmc.economy.LedgerReason;

class PlotCosmeticServiceTest {

    private HikariDataSource dataSource;
    private EconomyService economyService;
    private PlotCosmeticService plotCosmeticService;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.economyService = new EconomyService(new EconomyDao(dataSource));
        this.plotCosmeticService = new PlotCosmeticService(new PlotCosmeticDao(dataSource), economyService);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void buyDebitsTheBuyerAndRecordsThePurchase() throws Exception {
        UUID buyer = UUID.randomUUID();
        economyService.credit(buyer, 1000, LedgerReason.ADMIN_GIVE, null, null);

        PlotCosmeticService.BuyOutcome outcome = plotCosmeticService.buy(1, buyer, PlotCosmetic.BORDER_STONE, 1000L);

        assertEquals(PlotCosmeticService.BuyResult.PURCHASED, outcome.result());
        assertEquals(750L, outcome.newBalance()); // 1000 - 250
        assertEquals(750L, economyService.balanceOf(buyer));
        assertTrue(plotCosmeticService.isPurchased(1, PlotCosmetic.BORDER_STONE));
    }

    @Test
    void buyReportsInsufficientFundsAndChargesNothing() throws Exception {
        UUID buyer = UUID.randomUUID();
        economyService.credit(buyer, 10, LedgerReason.ADMIN_GIVE, null, null);

        PlotCosmeticService.BuyOutcome outcome = plotCosmeticService.buy(1, buyer, PlotCosmetic.BORDER_STONE, 1000L);

        assertEquals(PlotCosmeticService.BuyResult.INSUFFICIENT_FUNDS, outcome.result());
        assertEquals(10L, economyService.balanceOf(buyer)); // unchanged
        assertFalse(plotCosmeticService.isPurchased(1, PlotCosmetic.BORDER_STONE));
    }

    @Test
    void buyingAnAlreadyOwnedCosmeticReappliesForFree() throws Exception {
        UUID buyer = UUID.randomUUID();
        economyService.credit(buyer, 1000, LedgerReason.ADMIN_GIVE, null, null);
        plotCosmeticService.buy(1, buyer, PlotCosmetic.BORDER_STONE, 1000L);
        long balanceAfterFirstBuy = economyService.balanceOf(buyer);

        PlotCosmeticService.BuyOutcome outcome = plotCosmeticService.buy(1, buyer, PlotCosmetic.BORDER_STONE, 2000L);

        assertEquals(PlotCosmeticService.BuyResult.ALREADY_OWNED_REAPPLIED, outcome.result());
        assertEquals(balanceAfterFirstBuy, economyService.balanceOf(buyer)); // not charged again
    }

    @Test
    void freeCosmeticNeverTouchesTheBuyersBalance() throws Exception {
        UUID buyer = UUID.randomUUID(); // deliberately has 0 balance and never credited

        PlotCosmeticService.BuyOutcome outcome = plotCosmeticService.buy(1, buyer, PlotCosmetic.FLOOR_DEFAULT, 1000L);

        assertEquals(PlotCosmeticService.BuyResult.PURCHASED, outcome.result());
        assertEquals(0L, economyService.balanceOf(buyer));
        assertTrue(plotCosmeticService.isPurchased(1, PlotCosmetic.FLOOR_DEFAULT));
    }

    @Test
    void purchasedKeysForReturnsEveryCosmeticThisGuildOwns() throws Exception {
        UUID buyer = UUID.randomUUID();
        economyService.credit(buyer, 1000, LedgerReason.ADMIN_GIVE, null, null);
        plotCosmeticService.buy(1, buyer, PlotCosmetic.BORDER_STONE, 1000L);
        plotCosmeticService.buy(1, buyer, PlotCosmetic.FLOOR_SAND, 1000L);

        assertEquals(2, plotCosmeticService.purchasedKeysFor(1).size());
    }
}
