package org.rigelmc.protect.area;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Synchronous JDBC access to {@code rigel_protect_areas} - see {@link AreaRecord}. */
public final class AreaDao {

    private final DataSource dataSource;

    public AreaDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return the newly-inserted region's generated id. */
    public int insert(
            @NotNull String name, @NotNull String world, @NotNull String shapeType,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            int priority, @Nullable UUID owner, @Nullable UUID createdBy, long now) throws SQLException {
        String sql = "INSERT INTO rigel_protect_areas (name, name_lower, world, shape_type,"
                + " min_x, min_y, min_z, max_x, max_y, max_z, priority, enabled, owner_uuid,"
                + " created_by, created_at, updated_by, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, name.toLowerCase(Locale.ROOT));
            statement.setString(3, world);
            statement.setString(4, shapeType);
            statement.setInt(5, minX);
            statement.setInt(6, minY);
            statement.setInt(7, minZ);
            statement.setInt(8, maxX);
            statement.setInt(9, maxY);
            statement.setInt(10, maxZ);
            statement.setInt(11, priority);
            setUuidOrNull(statement, 12, owner);
            setUuidOrNull(statement, 13, createdBy);
            statement.setLong(14, now);
            setUuidOrNull(statement, 15, createdBy);
            statement.setLong(16, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Insert into rigel_protect_areas returned no generated key");
                }
                return keys.getInt(1);
            }
        }
    }

    public void updateBounds(
            int id, @NotNull String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            @Nullable UUID updatedBy, long now) throws SQLException {
        String sql = "UPDATE rigel_protect_areas SET world = ?, min_x = ?, min_y = ?, min_z = ?,"
                + " max_x = ?, max_y = ?, max_z = ?, updated_by = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, world);
            statement.setInt(2, minX);
            statement.setInt(3, minY);
            statement.setInt(4, minZ);
            statement.setInt(5, maxX);
            statement.setInt(6, maxY);
            statement.setInt(7, maxZ);
            setUuidOrNull(statement, 8, updatedBy);
            statement.setLong(9, now);
            statement.setInt(10, id);
            statement.executeUpdate();
        }
    }

    public void rename(int id, @NotNull String newName, @Nullable UUID updatedBy, long now) throws SQLException {
        String sql = "UPDATE rigel_protect_areas SET name = ?, name_lower = ?, updated_by = ?,"
                + " updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, newName);
            statement.setString(2, newName.toLowerCase(Locale.ROOT));
            setUuidOrNull(statement, 3, updatedBy);
            statement.setLong(4, now);
            statement.setInt(5, id);
            statement.executeUpdate();
        }
    }

    public void setPriority(int id, int priority, @Nullable UUID updatedBy, long now) throws SQLException {
        String sql = "UPDATE rigel_protect_areas SET priority = ?, updated_by = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, priority);
            setUuidOrNull(statement, 2, updatedBy);
            statement.setLong(3, now);
            statement.setInt(4, id);
            statement.executeUpdate();
        }
    }

    public void setEnabled(int id, boolean enabled, @Nullable UUID updatedBy, long now) throws SQLException {
        String sql = "UPDATE rigel_protect_areas SET enabled = ?, updated_by = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, enabled ? 1 : 0);
            setUuidOrNull(statement, 2, updatedBy);
            statement.setLong(3, now);
            statement.setInt(4, id);
            statement.executeUpdate();
        }
    }

    public void setOwner(int id, @Nullable UUID owner, @Nullable UUID updatedBy, long now) throws SQLException {
        String sql = "UPDATE rigel_protect_areas SET owner_uuid = ?, updated_by = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            setUuidOrNull(statement, 1, owner);
            setUuidOrNull(statement, 2, updatedBy);
            statement.setLong(3, now);
            statement.setInt(4, id);
            statement.executeUpdate();
        }
    }

    public void delete(int id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM rigel_protect_areas WHERE id = ?")) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }

    /** Wipes every region in one shot - backs {@code /protectarea clear confirm}. */
    public void deleteAll() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM rigel_protect_areas");
        }
    }

    @NotNull
    public Optional<AreaRecord> findByNameLower(@NotNull String nameLower) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT * FROM rigel_protect_areas WHERE name_lower = ?")) {
            statement.setString(1, nameLower);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Loaded once on plugin enable into {@link ProtectAreaService}'s in-memory cache. */
    @NotNull
    public List<AreaRecord> findAll() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT * FROM rigel_protect_areas ORDER BY name_lower");
                ResultSet rs = statement.executeQuery()) {
            List<AreaRecord> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        }
    }

    public int count() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM rigel_protect_areas")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * @param excludeId a region id to exclude from the results (the region being updated,
     *     so it never "overlaps itself"), or {@code null} for a fresh {@code create}
     * @return every <b>enabled</b> region in {@code world} whose bounds intersect the given
     *     box - backs the overlap-rejection policy in {@link ProtectAreaService}
     */
    @NotNull
    public List<AreaRecord> findOverlapping(
            @NotNull String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            @Nullable Integer excludeId) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM rigel_protect_areas WHERE enabled = 1 AND world = ?"
                        + " AND min_x <= ? AND max_x >= ? AND min_y <= ? AND max_y >= ? AND min_z <= ? AND max_z >= ?");
        if (excludeId != null) {
            sql.append(" AND id != ?");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, world);
            statement.setInt(2, maxX);
            statement.setInt(3, minX);
            statement.setInt(4, maxY);
            statement.setInt(5, minY);
            statement.setInt(6, maxZ);
            statement.setInt(7, minZ);
            if (excludeId != null) {
                statement.setInt(8, excludeId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<AreaRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        }
    }

    private static void setUuidOrNull(PreparedStatement statement, int index, @Nullable UUID uuid) throws SQLException {
        if (uuid != null) {
            statement.setString(index, uuid.toString());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    @Nullable
    private static UUID parseUuidOrNull(@Nullable String raw) {
        return raw != null ? UUID.fromString(raw) : null;
    }

    private static AreaRecord mapRow(ResultSet rs) throws SQLException {
        return new AreaRecord(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("world"),
                rs.getString("shape_type"),
                rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z"),
                rs.getInt("priority"),
                rs.getInt("enabled") != 0,
                parseUuidOrNull(rs.getString("owner_uuid")),
                parseUuidOrNull(rs.getString("created_by")),
                rs.getLong("created_at"),
                parseUuidOrNull(rs.getString("updated_by")),
                rs.getLong("updated_at"));
    }
}
