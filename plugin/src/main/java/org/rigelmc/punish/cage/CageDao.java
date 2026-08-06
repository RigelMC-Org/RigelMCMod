package org.rigelmc.punish.cage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/**
 * Synchronous JDBC access to {@code rigel_cages} - only the "is caged, and where" flag,
 * not the block snapshot itself (session-only, rebuilt fresh on apply). See {@link
 * CageService}'s javadoc.
 */
public final class CageDao {

    private final DataSource dataSource;

    public CageDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void upsert(@NotNull CageRecord record) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select =
                        connection.prepareStatement("SELECT 1 FROM rigel_cages WHERE uuid = ?")) {
                    select.setString(1, record.uuid().toString());
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE rigel_cages SET world = ?, center_x = ?, center_y = ?, center_z = ?,"
                                + " outer_material = ?, inner_material = ?, caged_by = ?, applied_at = ?"
                                + " WHERE uuid = ?"
                        : "INSERT INTO rigel_cages (world, center_x, center_y, center_z, outer_material,"
                                + " inner_material, caged_by, applied_at, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, record.world());
                    statement.setDouble(2, record.centerX());
                    statement.setDouble(3, record.centerY());
                    statement.setDouble(4, record.centerZ());
                    statement.setString(5, record.outerMaterial());
                    statement.setString(6, record.innerMaterial());
                    if (record.cagedBy() != null) {
                        statement.setString(7, record.cagedBy().toString());
                    } else {
                        statement.setNull(7, java.sql.Types.VARCHAR);
                    }
                    statement.setLong(8, record.appliedAt());
                    statement.setString(9, record.uuid().toString());
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

    public void delete(@NotNull UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM rigel_cages WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    @NotNull
    public Optional<CageRecord> find(@NotNull UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT * FROM rigel_cages WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Loaded once on plugin enable, so a cage survives a restart (re-applied on the player's next join). */
    @NotNull
    public List<CageRecord> findAll() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT * FROM rigel_cages");
                ResultSet rs = statement.executeQuery()) {
            List<CageRecord> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        }
    }

    private static CageRecord mapRow(ResultSet rs) throws SQLException {
        String cagedByRaw = rs.getString("caged_by");
        return new CageRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("world"),
                rs.getDouble("center_x"),
                rs.getDouble("center_y"),
                rs.getDouble("center_z"),
                rs.getString("outer_material"),
                rs.getString("inner_material"),
                cagedByRaw != null ? UUID.fromString(cagedByRaw) : null,
                rs.getLong("applied_at"));
    }
}
