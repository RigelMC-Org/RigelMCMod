package org.rigelmc.rank;

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
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.identity.PlayerIdentity;

class RankServiceTest {

    private HikariDataSource dataSource;
    private RankService rankService;
    private PlayerDao playerDao;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.playerDao = new PlayerDao(dataSource);
        this.rankService = new RankService(new RankRepository(dataSource), playerDao);
        rankService.initialize();
    }

    @AfterEach
    void tearDown() {
        // Close Hikari's pooled connections before JUnit tries to delete the @TempDir -
        // on Windows, a still-open SQLite file handle makes that cleanup fail.
        dataSource.close();
    }

    @Test
    void initializeSeedsTheDefaultLadder() {
        // default/moderator/admin/senior_admin - senior_admin is the top rank; Developer/
        // Executive/Owner are titles worn by senior admins, not separate ranks above it.
        assertEquals(4, rankService.allRanks().size());
        assertTrue(rankService.rank("senior_admin").isPresent());
        assertEquals(Rank.SENIOR_ADMIN.weight(), rankService.rank("senior_admin").get().weight());
    }

    @Test
    void unknownPlayerFallsBackToDefaultRank() throws Exception {
        Rank rank = rankService.rankOf(UUID.randomUUID());
        assertEquals("default", rank.id());
    }

    @Test
    void setRankPersistsAndIsReflectedByRankOf() throws Exception {
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, "Steve", PlayerIdentity.JAVA, System.currentTimeMillis());

        String previous = rankService.setRank(uuid, "senior_admin");

        assertEquals("default", previous);
        assertEquals("senior_admin", rankService.rankOf(uuid).id());
    }

    @Test
    void hasAtLeastComparesByWeightNotById() throws Exception {
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, "Alex", PlayerIdentity.JAVA, System.currentTimeMillis());
        rankService.setRank(uuid, "senior_admin");

        assertTrue(rankService.hasAtLeast(uuid, "moderator"));
        assertTrue(rankService.hasAtLeast(uuid, "senior_admin"));

        rankService.setRank(uuid, "moderator");
        assertFalse(rankService.hasAtLeast(uuid, "senior_admin"));
    }

    @Test
    void offlineIdentityIsTrustedLikeJava() throws Exception {
        UUID uuid = UUID.randomUUID();
        // rankOf() used to downgrade any stored elevated rank to default for an OFFLINE
        // identity (an offline/cracked/Eaglercraft UUID is derived purely from the
        // connecting username, so it's technically spoofable) - reverted at the user's
        // explicit request, since GeyserMC/Floodgate and Eaglercraft players are a core,
        // intended part of this server's real population, not an edge case, and the old
        // rule made it impossible for staff connecting through those paths to ever have
        // their assigned rank actually respected. See rankOf()'s own javadoc.
        playerDao.upsertOnLogin(uuid, "Steve", PlayerIdentity.OFFLINE, System.currentTimeMillis());
        rankService.setRank(uuid, "senior_admin");

        assertEquals("senior_admin", rankService.rankOf(uuid).id());
        assertTrue(rankService.hasAtLeast(uuid, "moderator"));
    }

    @Test
    void bedrockIdentityIsTrustedLikeJava() throws Exception {
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, "Steve", PlayerIdentity.BEDROCK, System.currentTimeMillis());
        rankService.setRank(uuid, "senior_admin");

        // Floodgate-verified Bedrock identities aren't spoofable the way OFFLINE ones
        // are - a stored elevated rank still applies normally.
        assertEquals("senior_admin", rankService.rankOf(uuid).id());
    }
}
