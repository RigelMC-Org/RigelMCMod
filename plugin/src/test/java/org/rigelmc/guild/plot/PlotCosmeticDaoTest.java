package org.rigelmc.guild.plot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class PlotCosmeticDaoTest {

    private HikariDataSource dataSource;
    private PlotCosmeticDao dao;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.dao = new PlotCosmeticDao(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void isPurchasedIsFalseForAnUnrecordedCosmetic() throws Exception {
        assertFalse(dao.isPurchased(1, "border-stone"));
    }

    @Test
    void recordPurchaseThenIsPurchasedRoundTrips() throws Exception {
        dao.recordPurchase(1, "border-stone", UUID.randomUUID(), 1000L);
        assertTrue(dao.isPurchased(1, "border-stone"));
    }

    @Test
    void purchaseIsScopedToItsOwnGuild() throws Exception {
        dao.recordPurchase(1, "border-stone", UUID.randomUUID(), 1000L);
        assertFalse(dao.isPurchased(2, "border-stone"));
    }

    @Test
    void findPurchasedKeysReturnsEveryCosmeticThisGuildOwns() throws Exception {
        UUID buyer = UUID.randomUUID();
        dao.recordPurchase(1, "border-stone", buyer, 1000L);
        dao.recordPurchase(1, "floor-sand", buyer, 1000L);
        dao.recordPurchase(2, "border-quartz", buyer, 1000L); // different guild - not in guild 1's set

        Set<String> owned = dao.findPurchasedKeys(1);

        assertEquals(Set.of("border-stone", "floor-sand"), owned);
    }
}
