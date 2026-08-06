package org.rigelmc.punish.ban;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.api.event.RigelPlayerBannedEvent.BanType;

/** Synchronous JDBC access to {@code rigel_bans}. */
public final class BanDao {

    private final DataSource dataSource;

    public BanDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Inserts a new ban row and returns it with its generated id populated. */
    @NotNull
    public Ban insert(@NotNull Ban ban) throws SQLException {
        String sql =
                "INSERT INTO rigel_bans (case_id, type, target_uuid, target_last_name, target_ip_hash,"
                        + " reason, banned_by_uuid, created_at, expires_at, active)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, ban.caseId());
            statement.setString(2, ban.type().name());
            statement.setString(3, ban.targetUuid() == null ? null : ban.targetUuid().toString());
            statement.setString(4, ban.targetLastName());
            statement.setString(5, ban.targetIpHash());
            statement.setString(6, ban.reason());
            statement.setString(7, ban.bannedByUuid() == null ? null : ban.bannedByUuid().toString());
            statement.setLong(8, ban.createdAt());
            if (ban.expiresAt() == null) {
                statement.setNull(9, java.sql.Types.BIGINT);
            } else {
                statement.setLong(9, ban.expiresAt());
            }
            statement.executeUpdate();

            long generatedId;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                generatedId = keys.next() ? keys.getLong(1) : -1;
            }
            return new Ban(
                    generatedId,
                    ban.caseId(),
                    ban.type(),
                    ban.targetUuid(),
                    ban.targetLastName(),
                    ban.targetIpHash(),
                    ban.reason(),
                    ban.bannedByUuid(),
                    ban.createdAt(),
                    ban.expiresAt(),
                    true,
                    null,
                    null);
        }
    }

    /** @return the most recent active, unexpired ban entry targeting this UUID by name, if any. */
    @NotNull
    public Optional<Ban> findActiveByUuid(UUID uuid, long nowEpochMillis) throws SQLException {
        String sql =
                "SELECT * FROM rigel_bans WHERE target_uuid = ? AND active = 1"
                        + " AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, nowEpochMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** @return the most recent active, unexpired ban entry targeting this IP hash, if any. */
    @NotNull
    public Optional<Ban> findActiveByIp(String ipHash, long nowEpochMillis) throws SQLException {
        String sql =
                "SELECT * FROM rigel_bans WHERE target_ip_hash = ? AND active = 1"
                        + " AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ipHash);
            statement.setLong(2, nowEpochMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** @return the {@code limit} most recently created ban entries, active or not - for the read-only web panel. */
    @NotNull
    public List<Ban> findRecent(int limit) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM rigel_bans ORDER BY created_at DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<Ban> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
                return results;
            }
        }
    }

    @NotNull
    public List<Ban> findByCaseId(String caseId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM rigel_bans WHERE case_id = ? ORDER BY created_at ASC")) {
            statement.setString(1, caseId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Ban> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
                return results;
            }
        }
    }

    /** Revokes a single ban entry by id. */
    public void revoke(long id, UUID revokedBy, long nowEpochMillis) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE rigel_bans SET active = 0, revoked_by_uuid = ?, revoked_at = ? WHERE id = ?")) {
            statement.setString(1, revokedBy == null ? null : revokedBy.toString());
            statement.setLong(2, nowEpochMillis);
            statement.setLong(3, id);
            statement.executeUpdate();
        }
    }

    /** Revokes every ban entry sharing the given case id (a whole {@code /permban} cascade). */
    public int revokeCase(String caseId, UUID revokedBy, long nowEpochMillis) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE rigel_bans SET active = 0, revoked_by_uuid = ?, revoked_at = ?"
                                + " WHERE case_id = ? AND active = 1")) {
            statement.setString(1, revokedBy == null ? null : revokedBy.toString());
            statement.setLong(2, nowEpochMillis);
            statement.setString(3, caseId);
            return statement.executeUpdate();
        }
    }

    /**
     * Revokes every currently-active ban entry directly targeting this UUID by name
     * (not IP entries from the same cascade case - see {@link #revokeCase} for that).
     *
     * @return the number of entries revoked (0 if none were active - callers should
     *     treat that as "player wasn't banned" rather than an error; this is the direct
     *     regression-test target for TFM's {@code /permban list} crash-on-empty bug)
     */
    public int revokeByUuid(UUID uuid, UUID revokedBy, long nowEpochMillis) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE rigel_bans SET active = 0, revoked_by_uuid = ?, revoked_at = ?"
                                + " WHERE target_uuid = ? AND active = 1")) {
            statement.setString(1, revokedBy == null ? null : revokedBy.toString());
            statement.setLong(2, nowEpochMillis);
            statement.setString(3, uuid.toString());
            return statement.executeUpdate();
        }
    }

    public int revokeByIp(String ipHash, UUID revokedBy, long nowEpochMillis) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE rigel_bans SET active = 0, revoked_by_uuid = ?, revoked_at = ?"
                                + " WHERE target_ip_hash = ? AND active = 1")) {
            statement.setString(1, revokedBy == null ? null : revokedBy.toString());
            statement.setLong(2, nowEpochMillis);
            statement.setString(3, ipHash);
            return statement.executeUpdate();
        }
    }

    private static Ban mapRow(ResultSet rs) throws SQLException {
        String targetUuidStr = rs.getString("target_uuid");
        String bannedByStr = rs.getString("banned_by_uuid");
        String revokedByStr = rs.getString("revoked_by_uuid");
        long expiresAtRaw = rs.getLong("expires_at");
        boolean expiresAtNull = rs.wasNull();
        long revokedAtRaw = rs.getLong("revoked_at");
        boolean revokedAtNull = rs.wasNull();

        return new Ban(
                rs.getLong("id"),
                rs.getString("case_id"),
                BanType.valueOf(rs.getString("type")),
                targetUuidStr == null ? null : UUID.fromString(targetUuidStr),
                rs.getString("target_last_name"),
                rs.getString("target_ip_hash"),
                rs.getString("reason"),
                bannedByStr == null ? null : UUID.fromString(bannedByStr),
                rs.getLong("created_at"),
                expiresAtNull ? null : expiresAtRaw,
                rs.getInt("active") != 0,
                revokedByStr == null ? null : UUID.fromString(revokedByStr),
                revokedAtNull ? null : revokedAtRaw);
    }
}
