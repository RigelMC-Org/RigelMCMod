package org.rigelmc.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

/**
 * Only the account-linking logic is unit-tested here - the Discord4J bot connection/message
 * handling in {@link DiscordBotManager} needs a live Discord gateway and isn't
 * exercised by this test suite (see the caveat on that class's javadoc).
 */
class DiscordLinkServiceTest {

    private HikariDataSource dataSource;
    private DiscordLinkService linkService;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.linkService = new DiscordLinkService(new DiscordLinkDao(dataSource));
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void validCodeLinksTheAccount() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String code = linkService.createLinkCode(uuid, Duration.ofMinutes(5), now);

        Optional<UUID> linked = linkService.consumeLinkCode(code, "discord-user-123", now + 1000);

        assertTrue(linked.isPresent());
        assertEquals(uuid, linked.get());
        assertEquals(Optional.of(uuid), linkService.resolveLinkedUuid("discord-user-123"));
    }

    @Test
    void expiredCodeDoesNotLink() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String code = linkService.createLinkCode(uuid, Duration.ofMinutes(5), now);

        Optional<UUID> linked = linkService.consumeLinkCode(code, "discord-user-123", now + Duration.ofMinutes(10).toMillis());

        assertTrue(linked.isEmpty());
        assertTrue(linkService.resolveLinkedUuid("discord-user-123").isEmpty());
    }

    @Test
    void codeIsSingleUse() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String code = linkService.createLinkCode(uuid, Duration.ofMinutes(5), now);

        assertTrue(linkService.consumeLinkCode(code, "discord-user-A", now).isPresent());
        // Same code submitted again (e.g. by someone who saw it) must not work twice.
        assertTrue(linkService.consumeLinkCode(code, "discord-user-B", now).isEmpty());
    }

    @Test
    void unknownCodeReturnsEmptyInsteadOfThrowing() throws Exception {
        Optional<UUID> linked = linkService.consumeLinkCode("NOSUCH", "discord-user-1", System.currentTimeMillis());
        assertTrue(linked.isEmpty());
    }

    @Test
    void unlinkRemovesAnExistingLinkAndReturnsFalseIfNoneExists() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String code = linkService.createLinkCode(uuid, Duration.ofMinutes(5), now);
        linkService.consumeLinkCode(code, "discord-user-1", now);

        assertTrue(linkService.unlink(uuid));
        assertFalse(linkService.unlink(uuid)); // second call: nothing left to unlink
        assertTrue(linkService.resolveLinkedUuid("discord-user-1").isEmpty());
    }
}
