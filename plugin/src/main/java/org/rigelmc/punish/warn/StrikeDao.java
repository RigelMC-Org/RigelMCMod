package org.rigelmc.punish.warn;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_strikes} - see {@link StrikeService}. */
public final class StrikeDao {

    private final DataSource dataSource;

    public StrikeDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    public Optional<StrikeRecord> find(@NotNull UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT * FROM rigel_strikes WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public void upsert(@NotNull UUID uuid, int strikeCount, long lastStrikeAt) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select =
                        connection.prepareStatement("SELECT 1 FROM rigel_strikes WHERE uuid = ?")) {
                    select.setString(1, uuid.toString());
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE rigel_strikes SET strike_count = ?, last_strike_at = ? WHERE uuid = ?"
                        : "INSERT INTO rigel_strikes (strike_count, last_strike_at, uuid) VALUES (?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, strikeCount);
                    statement.setLong(2, lastStrikeAt);
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

    private static StrikeRecord mapRow(ResultSet rs) throws SQLException {
        return new StrikeRecord(
                UUID.fromString(rs.getString("uuid")), rs.getInt("strike_count"), rs.getLong("last_strike_at"));
    }
}
