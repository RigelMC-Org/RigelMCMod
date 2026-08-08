package org.rigelmc.punish.appeal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Synchronous JDBC access to {@code rigel_ban_appeals}. */
public final class AppealDao {

    private final DataSource dataSource;

    public AppealDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Inserts a new, {@code PENDING} appeal and returns it with its generated id populated. */
    @NotNull
    public Appeal insert(@NotNull Appeal appeal) throws SQLException {
        String sql =
                "INSERT INTO rigel_ban_appeals (ban_reference, ban_id, message, contact, submitted_at,"
                        + " submitter_ip_hash, status) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, appeal.banReference());
            statement.setLong(2, appeal.banId());
            statement.setString(3, appeal.message());
            statement.setString(4, appeal.contact());
            statement.setLong(5, appeal.submittedAt());
            statement.setString(6, appeal.submitterIpHash());
            statement.setString(7, appeal.status().name());
            statement.executeUpdate();

            long generatedId;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                generatedId = keys.next() ? keys.getLong(1) : -1;
            }
            return new Appeal(
                    generatedId, appeal.banReference(), appeal.banId(), appeal.message(), appeal.contact(),
                    appeal.submittedAt(), appeal.submitterIpHash(), appeal.status(), null, null, null);
        }
    }

    @NotNull
    public Optional<Appeal> findById(long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT * FROM rigel_ban_appeals WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** @return a still-{@code PENDING} appeal already on file for this reference, if any - blocks duplicate submissions. */
    @NotNull
    public Optional<Appeal> findPendingByBanReference(@NotNull String banReference) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM rigel_ban_appeals WHERE ban_reference = ? AND status = 'PENDING'"
                                + " ORDER BY submitted_at DESC LIMIT 1")) {
            statement.setString(1, banReference);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Marks a decision, but only if the appeal is still {@code PENDING} - the {@code WHERE
     * status = 'PENDING'} guard makes this the single source of truth against a double-
     * decide race (two staff clicking Approve/Deny on the same message within moments of
     * each other), not a check-then-act done separately in the caller.
     *
     * @return {@code true} if this call was the one that actually recorded the decision;
     *     {@code false} if the appeal was already decided (or doesn't exist) - the caller
     *     must not treat a decision as taken effect unless this returns {@code true}
     */
    public boolean decide(long id, @NotNull Appeal.Status status, @Nullable UUID decidedBy, long nowEpochMillis)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE rigel_ban_appeals SET status = ?, decided_by_uuid = ?, decided_at = ?"
                                + " WHERE id = ? AND status = 'PENDING'")) {
            statement.setString(1, status.name());
            statement.setString(2, decidedBy == null ? null : decidedBy.toString());
            statement.setLong(3, nowEpochMillis);
            statement.setLong(4, id);
            return statement.executeUpdate() > 0;
        }
    }

    public void setDiscordMessageId(long id, @NotNull String discordMessageId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE rigel_ban_appeals SET discord_message_id = ? WHERE id = ?")) {
            statement.setString(1, discordMessageId);
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    private static Appeal mapRow(ResultSet rs) throws SQLException {
        String decidedByStr = rs.getString("decided_by_uuid");
        long decidedAtRaw = rs.getLong("decided_at");
        boolean decidedAtNull = rs.wasNull();

        return new Appeal(
                rs.getLong("id"),
                rs.getString("ban_reference"),
                rs.getLong("ban_id"),
                rs.getString("message"),
                rs.getString("contact"),
                rs.getLong("submitted_at"),
                rs.getString("submitter_ip_hash"),
                Appeal.Status.valueOf(rs.getString("status")),
                rs.getString("discord_message_id"),
                decidedByStr == null ? null : UUID.fromString(decidedByStr),
                decidedAtNull ? null : decidedAtRaw);
    }
}
