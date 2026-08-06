package org.rigelmc.world;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_spawn} - a single logical row (id = 1). */
public final class SpawnDao {

    private static final int ROW_ID = 1;

    private final DataSource dataSource;

    public SpawnDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void upsert(@NotNull SpawnRecord record) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select =
                        connection.prepareStatement("SELECT 1 FROM rigel_spawn WHERE id = ?")) {
                    select.setInt(1, ROW_ID);
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE rigel_spawn SET world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?,"
                                + " set_by = ?, set_at = ? WHERE id = ?"
                        : "INSERT INTO rigel_spawn (world, x, y, z, yaw, pitch, set_by, set_at, id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, record.world());
                    statement.setDouble(2, record.x());
                    statement.setDouble(3, record.y());
                    statement.setDouble(4, record.z());
                    statement.setFloat(5, record.yaw());
                    statement.setFloat(6, record.pitch());
                    if (record.setBy() != null) {
                        statement.setString(7, record.setBy().toString());
                    } else {
                        statement.setNull(7, Types.VARCHAR);
                    }
                    statement.setLong(8, record.setAt());
                    statement.setInt(9, ROW_ID);
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

    @NotNull
    public Optional<SpawnRecord> find() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT * FROM rigel_spawn WHERE id = ?")) {
            statement.setInt(1, ROW_ID);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String setByRaw = rs.getString("set_by");
                return Optional.of(new SpawnRecord(
                        rs.getString("world"),
                        rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                        rs.getFloat("yaw"), rs.getFloat("pitch"),
                        setByRaw != null ? UUID.fromString(setByRaw) : null,
                        rs.getLong("set_at")));
            }
        }
    }
}
