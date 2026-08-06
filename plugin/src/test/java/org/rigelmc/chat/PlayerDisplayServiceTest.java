package org.rigelmc.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariDataSource;
import java.io.StringReader;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.TestDatabase;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.identity.PlayerIdentity;
import org.rigelmc.nick.NickDao;
import org.rigelmc.nick.NickService;
import org.rigelmc.rank.PrefixService;
import org.rigelmc.rank.RankRepository;
import org.rigelmc.rank.RankService;
import org.rigelmc.rank.TitleRepository;
import org.rigelmc.rank.TitleService;
import org.rigelmc.tag.TagService;

/**
 * Covers the tag-as-prefix-override behavior specifically: a {@code /tag} fully replaces
 * whatever rank/title prefix would otherwise show, becoming the sole prefix for the rest
 * of the session, regardless of rank. {@link PlayerDisplayService#fullDisplayFor} takes a
 * plain UUID/name, not a live {@code Player}, so this is fully testable without
 * MockBukkit.
 */
class PlayerDisplayServiceTest {

    private static final RigelConfig NO_OVERRIDES =
            new RigelConfig(YamlConfiguration.loadConfiguration(new StringReader("")));

    private HikariDataSource dataSource;
    private PlayerDao playerDao;
    private RankService rankService;
    private PrefixService prefixService;
    private TagService tagService;
    private PlayerDisplayService displayService;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.playerDao = new PlayerDao(dataSource);
        this.rankService = new RankService(new RankRepository(dataSource), playerDao);
        rankService.initialize();
        TitleService titleService = new TitleService(new TitleRepository(dataSource));
        titleService.initialize();
        this.prefixService = new PrefixService(rankService, titleService);
        NickService nickService = new NickService(new NickDao(dataSource));
        this.tagService = new TagService();
        this.displayService = new PlayerDisplayService(prefixService, nickService, tagService);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void defaultRankNoTagShowsOpPrefix() throws Exception {
        UUID uuid = newPlayer("Steve");
        // refresh() alone (inside newPlayer) can't confirm live op status - see
        // rank.PrefixService's class javadoc - applyOpStatus is what actually shows it,
        // exactly as core.AutoOpModule/core.PlayerLoginListener call it in production.
        prefixService.applyOpStatus(uuid, true);

        String result = plain(displayService.fullDisplayFor(uuid, "Steve"));

        assertEquals("[OP] Steve", result);
    }

    @Test
    void defaultRankWithTagShowsTagInsteadOfOp() throws Exception {
        UUID uuid = newPlayer("Steve");
        tagService.set(uuid, "&aBuilder");

        String result = plain(displayService.fullDisplayFor(uuid, "Steve"));

        assertEquals("Builder Steve", result); // no "[OP]" anywhere
    }

    @Test
    void rankedPlayerNoTagShowsRankPrefix() throws Exception {
        UUID uuid = newPlayer("Alex");
        rankService.setRank(uuid, "moderator");
        prefixService.refresh(uuid, NO_OVERRIDES);

        String result = plain(displayService.fullDisplayFor(uuid, "Alex"));

        assertEquals("[Mod] Alex", result);
    }

    @Test
    void rankedPlayerWithTagShowsOnlyTheTag() throws Exception {
        UUID uuid = newPlayer("Alex");
        rankService.setRank(uuid, "moderator");
        prefixService.refresh(uuid, NO_OVERRIDES);
        tagService.set(uuid, "&bEventHost");

        String result = plain(displayService.fullDisplayFor(uuid, "Alex"));

        // A set tag is the sole prefix, regardless of rank - the [Mod] prefix is
        // completely replaced, not shown alongside it.
        assertEquals("EventHost Alex", result);
    }

    private UUID newPlayer(String name) throws Exception {
        UUID uuid = UUID.randomUUID();
        playerDao.upsertOnLogin(uuid, name, PlayerIdentity.JAVA, System.currentTimeMillis());
        prefixService.refresh(uuid, NO_OVERRIDES);
        return uuid;
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
