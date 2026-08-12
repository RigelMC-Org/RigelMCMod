package org.rigelmc.vote;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_vote_records} - see {@link VoteRecord}. */
public final class VoteRecordDao {

    private final DataSource dataSource;

    public VoteRecordDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    public Optional<VoteRecord> find(@NotNull UUID uuid) throws SQLException {
        String sql = "SELECT uuid, total_votes, current_streak, last_vote_at, updated_at FROM rigel_vote_records WHERE uuid = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Inserts or overwrites this player's full record - explicit exists-check-then-insert-
     * or-update, matching {@code AreaMemberDao#add}'s own portable-across-SQLite/MySQL
     * upsert shape rather than a dialect-specific statement.
     */
    public void upsert(@NotNull VoteRecord record) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select = connection.prepareStatement("SELECT 1 FROM rigel_vote_records WHERE uuid = ?")) {
                    select.setString(1, record.uuid().toString());
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE rigel_vote_records SET total_votes = ?, current_streak = ?, last_vote_at = ?,"
                                + " updated_at = ? WHERE uuid = ?"
                        : "INSERT INTO rigel_vote_records (total_votes, current_streak, last_vote_at, updated_at, uuid)"
                                + " VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, record.totalVotes());
                    statement.setInt(2, record.currentStreak());
                    if (record.lastVoteAt() != null) {
                        statement.setLong(3, record.lastVoteAt());
                    } else {
                        statement.setNull(3, java.sql.Types.BIGINT);
                    }
                    statement.setLong(4, record.updatedAt());
                    statement.setString(5, record.uuid().toString());
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

    private static VoteRecord mapRow(ResultSet rs) throws SQLException {
        long lastVoteAtRaw = rs.getLong("last_vote_at");
        Long lastVoteAt = rs.wasNull() ? null : lastVoteAtRaw;
        return new VoteRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getInt("total_votes"),
                rs.getInt("current_streak"),
                lastVoteAt,
                rs.getLong("updated_at"));
    }
}
