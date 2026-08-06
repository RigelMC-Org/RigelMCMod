package org.rigelmc.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Synchronous JDBC access to the append-only {@code rigel_audit_log}. */
public final class AuditLogDao {

    private final DataSource dataSource;

    public AuditLogDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void insert(
            @Nullable UUID actorUuid,
            @NotNull String actionType,
            @Nullable UUID targetUuid,
            @Nullable String detail,
            long nowEpochMillis)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO rigel_audit_log (actor_uuid, action_type, target_uuid, detail, created_at)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, actorUuid == null ? null : actorUuid.toString());
            statement.setString(2, actionType);
            statement.setString(3, targetUuid == null ? null : targetUuid.toString());
            statement.setString(4, detail);
            statement.setLong(5, nowEpochMillis);
            statement.executeUpdate();
        }
    }

    /** @return the most recent entries for a given target UUID, newest first. */
    @NotNull
    public List<AuditEntry> findRecentForTarget(@NotNull UUID targetUuid, int limit) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT actor_uuid, action_type, target_uuid, detail, created_at FROM rigel_audit_log"
                                + " WHERE target_uuid = ? ORDER BY created_at DESC LIMIT ?")) {
            statement.setString(1, targetUuid.toString());
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<AuditEntry> entries = new ArrayList<>();
                while (rs.next()) {
                    String actorStr = rs.getString("actor_uuid");
                    entries.add(new AuditEntry(
                            actorStr == null ? null : UUID.fromString(actorStr),
                            rs.getString("action_type"),
                            targetUuid,
                            rs.getString("detail"),
                            rs.getLong("created_at")));
                }
                return entries;
            }
        }
    }

    public record AuditEntry(UUID actorUuid, String actionType, UUID targetUuid, String detail, long createdAt) {}
}
