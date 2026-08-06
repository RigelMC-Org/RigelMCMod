package org.rigelmc.punish.mute;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_mutes} - one row per currently-muted player. */
public final class MuteDao {

    private final DataSource dataSource;

    public MuteDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Upserts a mute row for this player (replacing any prior mute). */
    public void upsert(UUID uuid, UUID mutedBy, String reason, long nowEpochMillis, Long expiresAtEpochMillis)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select =
                        connection.prepareStatement("SELECT 1 FROM rigel_mutes WHERE uuid = ?")) {
                    select.setString(1, uuid.toString());
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE rigel_mutes SET muted_by_uuid = ?, reason = ?, created_at = ?, expires_at = ?"
                                + " WHERE uuid = ?"
                        : "INSERT INTO rigel_mutes (muted_by_uuid, reason, created_at, expires_at, uuid)"
                                + " VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, mutedBy == null ? null : mutedBy.toString());
                    statement.setString(2, reason);
                    statement.setLong(3, nowEpochMillis);
                    if (expiresAtEpochMillis == null) {
                        statement.setNull(4, java.sql.Types.BIGINT);
                    } else {
                        statement.setLong(4, expiresAtEpochMillis);
                    }
                    statement.setString(5, uuid.toString());
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

    /** @return 0 if the player wasn't muted - not an error, mirrors {@code BanDao}'s defensive pattern. */
    public int delete(UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM rigel_mutes WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            return statement.executeUpdate();
        }
    }

    @NotNull
    public Optional<MuteRecord> find(UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT muted_by_uuid, reason, created_at, expires_at FROM rigel_mutes WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String mutedByStr = rs.getString("muted_by_uuid");
                long expiresAtRaw = rs.getLong("expires_at");
                boolean expiresAtNull = rs.wasNull();
                return Optional.of(new MuteRecord(
                        uuid,
                        mutedByStr == null ? null : UUID.fromString(mutedByStr),
                        rs.getString("reason"),
                        rs.getLong("created_at"),
                        expiresAtNull ? null : expiresAtRaw));
            }
        }
    }

    /** @return every currently-muted player row (may include some already past their expiry - see {@link MuteRecord#isExpired}). */
    @NotNull
    public java.util.List<MuteRecord> findAll() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT uuid, muted_by_uuid, reason, created_at, expires_at FROM rigel_mutes");
                ResultSet rs = statement.executeQuery()) {
            java.util.List<MuteRecord> results = new java.util.ArrayList<>();
            while (rs.next()) {
                String mutedByStr = rs.getString("muted_by_uuid");
                long expiresAtRaw = rs.getLong("expires_at");
                boolean expiresAtNull = rs.wasNull();
                results.add(new MuteRecord(
                        UUID.fromString(rs.getString("uuid")),
                        mutedByStr == null ? null : UUID.fromString(mutedByStr),
                        rs.getString("reason"),
                        rs.getLong("created_at"),
                        expiresAtNull ? null : expiresAtRaw));
            }
            return results;
        }
    }

    public record MuteRecord(UUID uuid, UUID mutedBy, String reason, long createdAt, Long expiresAt) {
        public boolean isExpired(long nowEpochMillis) {
            return expiresAt != null && expiresAt <= nowEpochMillis;
        }
    }
}
