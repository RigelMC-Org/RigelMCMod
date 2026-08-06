package org.rigelmc.discord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_discord_links} and {@code rigel_discord_link_codes}. */
public final class DiscordLinkDao {

    private final DataSource dataSource;

    public DiscordLinkDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void insertCode(@NotNull String code, @NotNull UUID uuid, long nowEpochMillis, long expiresAtEpochMillis)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO rigel_discord_link_codes (code, uuid, created_at, expires_at)"
                                + " VALUES (?, ?, ?, ?)")) {
            statement.setString(1, code);
            statement.setString(2, uuid.toString());
            statement.setLong(3, nowEpochMillis);
            statement.setLong(4, expiresAtEpochMillis);
            statement.executeUpdate();
        }
    }

    @NotNull
    public Optional<PendingCode> findCode(@NotNull String code) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT uuid, expires_at FROM rigel_discord_link_codes WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PendingCode(
                        UUID.fromString(rs.getString("uuid")), rs.getLong("expires_at")));
            }
        }
    }

    public void deleteCode(@NotNull String code) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM rigel_discord_link_codes WHERE code = ?")) {
            statement.setString(1, code);
            statement.executeUpdate();
        }
    }

    public void upsertLink(@NotNull UUID uuid, @NotNull String discordUserId, long nowEpochMillis) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select =
                        connection.prepareStatement("SELECT 1 FROM rigel_discord_links WHERE uuid = ?")) {
                    select.setString(1, uuid.toString());
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE rigel_discord_links SET discord_user_id = ?, linked_at = ? WHERE uuid = ?"
                        : "INSERT INTO rigel_discord_links (discord_user_id, linked_at, uuid) VALUES (?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, discordUserId);
                    statement.setLong(2, nowEpochMillis);
                    statement.setString(3, uuid.toString());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public int deleteLinkByUuid(@NotNull UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM rigel_discord_links WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            return statement.executeUpdate();
        }
    }

    @NotNull
    public Optional<UUID> findUuidByDiscordUserId(@NotNull String discordUserId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT uuid FROM rigel_discord_links WHERE discord_user_id = ?")) {
            statement.setString(1, discordUserId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(UUID.fromString(rs.getString("uuid"))) : Optional.empty();
            }
        }
    }

    @NotNull
    public Optional<String> findDiscordUserIdByUuid(@NotNull UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT discord_user_id FROM rigel_discord_links WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("discord_user_id")) : Optional.empty();
            }
        }
    }

    public record PendingCode(UUID uuid, long expiresAt) {
        public boolean isExpired(long nowEpochMillis) {
            return expiresAt <= nowEpochMillis;
        }
    }
}
