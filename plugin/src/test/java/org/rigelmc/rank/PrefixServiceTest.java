package org.rigelmc.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariDataSource;
import java.io.StringReader;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.TestDatabase;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.identity.PlayerIdentity;

class PrefixServiceTest {

    private static final RigelConfig NO_OVERRIDES = configFrom("");

    private HikariDataSource dataSource;
    private RankService rankService;
    private TitleService titleService;
    private PlayerDao playerDao;
    private PrefixService prefixService;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.playerDao = new PlayerDao(dataSource);
        this.rankService = new RankService(new RankRepository(dataSource), playerDao);
        rankService.initialize();
        this.titleService = new TitleService(new TitleRepository(dataSource));
        titleService.initialize();
        this.prefixService = new PrefixService(rankService, titleService);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void unrankedOppedPlayerGetsTheDefaultOpPrefix() throws Exception {
        // Everyone is vanilla-op'd on a Free-OP server (core.AutoOpModule) - an unranked
        // player who's currently op'd shows "[OP]" rather than nothing, so staff are
        // visually distinguishable from the auto-op'd masses. See Rank.DEFAULT's javadoc.
        // refresh() alone can't confirm live op status (no Player reference, runs off the
        // main thread) - applyOpStatus is what actually shows/hides the bracket, exactly
        // as core.AutoOpModule and core.PlayerLoginListener call it in production.
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, "Steve", PlayerIdentity.JAVA, System.currentTimeMillis());

        prefixService.refresh(uuid, NO_OVERRIDES);
        prefixService.applyOpStatus(uuid, true);

        assertEquals(plainText(Rank.DEFAULT.prefix()), plainText(prefixService.prefixFor(uuid)));
    }

    @Test
    void refreshAloneHidesTheOpPrefixUntilOpStatusIsConfirmed() throws Exception {
        // Regression test for the reported bug: a deopped default-rank player kept
        // showing "[OP]" in chat because nothing ever consulted live isOp() - see
        // PrefixService's class javadoc. refresh() by itself must leave the bracket
        // hidden (safe default) rather than assuming op status.
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, "Steve", PlayerIdentity.JAVA, System.currentTimeMillis());

        prefixService.refresh(uuid, NO_OVERRIDES);

        assertEquals("", plainText(prefixService.prefixFor(uuid)));
    }

    @Test
    void deoppedPlayerLosesTheOpPrefix() throws Exception {
        // The literal bug report: "if deopped, the OP prefix must not show" - confirms
        // applyOpStatus(uuid, false) actually clears a previously-shown bracket, not just
        // that it's never shown in the first place.
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, "Steve", PlayerIdentity.JAVA, System.currentTimeMillis());
        prefixService.refresh(uuid, NO_OVERRIDES);
        prefixService.applyOpStatus(uuid, true);
        assertEquals(plainText(Rank.DEFAULT.prefix()), plainText(prefixService.prefixFor(uuid)));

        prefixService.applyOpStatus(uuid, false);

        assertEquals("", plainText(prefixService.prefixFor(uuid)));
    }

    @Test
    void rankPrefixIsComposedFromLegacyColorCodes() throws Exception {
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, "Alex", PlayerIdentity.JAVA, System.currentTimeMillis());
        rankService.setRank(uuid, "moderator");

        prefixService.refresh(uuid, NO_OVERRIDES);

        assertEquals(plainText(Rank.MODERATOR.prefix()), plainText(prefixService.prefixFor(uuid)));
    }

    @Test
    void titleOutranksRankSoOnlyTheTitlePrefixShows() throws Exception {
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, "Notch", PlayerIdentity.JAVA, System.currentTimeMillis());
        rankService.setRank(uuid, "senior_admin");
        titleService.grant(uuid, Title.OWNER.id(), null, System.currentTimeMillis());

        prefixService.refresh(uuid, NO_OVERRIDES);

        // Every title outranks every rank (Title#weight) - exactly one prefix shows, the
        // higher-weight one, never both stacked.
        assertEquals(plainText(Title.OWNER.prefix()), plainText(prefixService.prefixFor(uuid)));
    }

    @Test
    void rankAloneShowsWhenNoTitleIsHeld() throws Exception {
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, "Alex", PlayerIdentity.JAVA, System.currentTimeMillis());
        rankService.setRank(uuid, "senior_admin");

        prefixService.refresh(uuid, NO_OVERRIDES);

        assertEquals(plainText(Rank.SENIOR_ADMIN.prefix()), plainText(prefixService.prefixFor(uuid)));
    }

    @Test
    void configuredRankPrefixOverridesTheHardcodedDefault() throws Exception {
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, "Alex", PlayerIdentity.JAVA, System.currentTimeMillis());
        rankService.setRank(uuid, "moderator");
        RigelConfig overridden = configFrom("""
                rank-prefixes:
                  moderator: "&8[&dMVP&8] &r"
                """);

        prefixService.refresh(uuid, overridden);

        assertEquals("[MVP] ", plainText(prefixService.prefixFor(uuid)));
    }

    private static String plainText(String legacyText) {
        return plainText(LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText));
    }

    private static String plainText(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static RigelConfig configFrom(String yaml) {
        return new RigelConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }
}
