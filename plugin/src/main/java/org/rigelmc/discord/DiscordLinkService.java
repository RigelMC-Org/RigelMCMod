package org.rigelmc.discord;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Account-linking business logic backing {@code /discord link}/{@code unlink} and the
 * bot's {@code /link} Discord slash command - see docs/architecture.md "Discord bridge &
 * admin chat". Pure JDBC, no Discord4J/Bukkit types, so the linking logic itself is
 * unit-testable without a live Discord connection.
 */
public final class DiscordLinkService {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DiscordLinkDao dao;

    public DiscordLinkService(@NotNull DiscordLinkDao dao) {
        this.dao = dao;
    }

    /** Generates and stores a new one-time link code for this player, expiring per config TTL. */
    @NotNull
    public String createLinkCode(@NotNull UUID uuid, @NotNull Duration ttl, long nowEpochMillis) throws SQLException {
        String code = generateCode();
        dao.insertCode(code, uuid, nowEpochMillis, nowEpochMillis + ttl.toMillis());
        return code;
    }

    /**
     * Consumes a code submitted via the {@code /link} slash command (e.g.
     * {@code /link code:ABC123}). The code is deleted
     * whether or not it was valid - a code is single-use regardless of outcome, so a
     * leaked/guessed code can't be retried indefinitely.
     *
     * @return the now-linked player's UUID, or empty if the code was invalid/expired
     */
    @NotNull
    public Optional<UUID> consumeLinkCode(
            @NotNull String code, @NotNull String discordUserId, long nowEpochMillis) throws SQLException {
        Optional<DiscordLinkDao.PendingCode> pending = dao.findCode(code);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        dao.deleteCode(code);
        if (pending.get().isExpired(nowEpochMillis)) {
            return Optional.empty();
        }
        UUID uuid = pending.get().uuid();
        dao.upsertLink(uuid, discordUserId, nowEpochMillis);
        return Optional.of(uuid);
    }

    /** @return {@code true} if a link existed and was removed */
    public boolean unlink(@NotNull UUID uuid) throws SQLException {
        return dao.deleteLinkByUuid(uuid) > 0;
    }

    @NotNull
    public Optional<UUID> resolveLinkedUuid(@NotNull String discordUserId) throws SQLException {
        return dao.findUuidByDiscordUserId(discordUserId);
    }

    @NotNull
    public Optional<String> resolveDiscordUserId(@NotNull UUID uuid) throws SQLException {
        return dao.findDiscordUserIdByUuid(uuid);
    }

    @NotNull
    private static String generateCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return builder.toString();
    }
}
