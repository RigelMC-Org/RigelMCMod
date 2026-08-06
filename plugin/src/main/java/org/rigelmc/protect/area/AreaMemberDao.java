package org.rigelmc.protect.area;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Synchronous JDBC access to {@code rigel_protect_area_members} - the per-region trusted-
 * bypass list. See {@link AreaFlag}'s javadoc for why this (not rank) is the actual
 * improvement over TFM's binary any-admin-bypasses model.
 */
public final class AreaMemberDao {

    private final DataSource dataSource;

    public AreaMemberDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Idempotent - adding an already-present member is a silent no-op, not an error.
     * Explicit exists-check-then-insert rather than {@code INSERT OR IGNORE}/{@code ON
     * CONFLICT} - those are dialect-specific (SQLite/MySQL disagree on the exact syntax)
     * and this project supports both, matching {@code CageDao}/{@code SpawnDao}'s own
     * portable upsert shape.
     */
    public void add(int areaId, @NotNull UUID memberUuid, @Nullable UUID addedBy, long now) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT 1 FROM rigel_protect_area_members WHERE area_id = ? AND member_uuid = ?")) {
                    select.setInt(1, areaId);
                    select.setString(2, memberUuid.toString());
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                if (!exists) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO rigel_protect_area_members (area_id, member_uuid, added_by, added_at)"
                                    + " VALUES (?, ?, ?, ?)")) {
                        insert.setInt(1, areaId);
                        insert.setString(2, memberUuid.toString());
                        if (addedBy != null) {
                            insert.setString(3, addedBy.toString());
                        } else {
                            insert.setNull(3, Types.VARCHAR);
                        }
                        insert.setLong(4, now);
                        insert.executeUpdate();
                    }
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

    public void remove(int areaId, @NotNull UUID memberUuid) throws SQLException {
        String sql = "DELETE FROM rigel_protect_area_members WHERE area_id = ? AND member_uuid = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, areaId);
            statement.setString(2, memberUuid.toString());
            statement.executeUpdate();
        }
    }

    /** Loaded once per region on plugin enable into {@link ProtectAreaService}'s in-memory cache. */
    @NotNull
    public Set<UUID> findForArea(int areaId) throws SQLException {
        String sql = "SELECT member_uuid FROM rigel_protect_area_members WHERE area_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, areaId);
            try (ResultSet rs = statement.executeQuery()) {
                Set<UUID> result = new HashSet<>();
                while (rs.next()) {
                    result.add(UUID.fromString(rs.getString("member_uuid")));
                }
                return result;
            }
        }
    }
}
