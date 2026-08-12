package org.rigelmc.protect.area;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Map;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/**
 * Synchronous JDBC access to {@code rigel_protect_area_flags} - only per-region
 * <b>overrides</b> are stored; a flag with no row here uses {@link AreaFlag#defaultValue()}.
 * See {@link AreaRegion#effectiveFlag(AreaFlag)}.
 */
public final class AreaFlagDao {

    private final DataSource dataSource;

    public AreaFlagDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Sets (or replaces) this region's override for {@code flag}. Explicit exists-check-
     * then-insert/update rather than a dialect-specific upsert statement - see {@link
     * AreaMemberDao#add}'s javadoc for why.
     */
    public void setOverride(int areaId, @NotNull AreaFlag flag, boolean value) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT 1 FROM rigel_protect_area_flags WHERE area_id = ? AND flag_key = ?")) {
                    select.setInt(1, areaId);
                    select.setString(2, flag.key());
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE rigel_protect_area_flags SET flag_value = ? WHERE area_id = ? AND flag_key = ?"
                        : "INSERT INTO rigel_protect_area_flags (flag_value, area_id, flag_key) VALUES (?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, value ? "ALLOW" : "DENY");
                    statement.setInt(2, areaId);
                    statement.setString(3, flag.key());
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

    /** Removes this region's override for {@code flag}, reverting it back to {@link AreaFlag#defaultValue()}. */
    public void clearOverride(int areaId, @NotNull AreaFlag flag) throws SQLException {
        String sql = "DELETE FROM rigel_protect_area_flags WHERE area_id = ? AND flag_key = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, areaId);
            statement.setString(2, flag.key());
            statement.executeUpdate();
        }
    }

    /**
     * Every flag-override row for one area in a single statement - used by {@link
     * ProtectAreaService#delete} so a deleted area doesn't leave orphaned flag rows behind.
     * See {@link AreaMemberDao#deleteForArea}'s javadoc for the same rationale.
     */
    public void deleteForArea(int areaId) throws SQLException {
        String sql = "DELETE FROM rigel_protect_area_flags WHERE area_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, areaId);
            statement.executeUpdate();
        }
    }

    /** Every flag-override row for every area - used by {@link ProtectAreaService#clearAll}. */
    public void deleteAll() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM rigel_protect_area_flags");
        }
    }

    /** Loaded once per region on plugin enable into {@link ProtectAreaService}'s in-memory cache. */
    @NotNull
    public Map<AreaFlag, Boolean> findForArea(int areaId) throws SQLException {
        String sql = "SELECT flag_key, flag_value FROM rigel_protect_area_flags WHERE area_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, areaId);
            try (ResultSet rs = statement.executeQuery()) {
                Map<AreaFlag, Boolean> result = new EnumMap<>(AreaFlag.class);
                while (rs.next()) {
                    AreaFlag flag = AreaFlag.byKey(rs.getString("flag_key"));
                    if (flag != null) { // ignore an unknown/stale flag_key rather than fail the whole load
                        result.put(flag, "ALLOW".equals(rs.getString("flag_value")));
                    }
                }
                return result;
            }
        }
    }
}
