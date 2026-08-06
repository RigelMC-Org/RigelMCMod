package org.rigelmc.data.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_world_state} - currently just last-wipe timestamps. */
public final class WorldStateDao {

    private final DataSource dataSource;

    public WorldStateDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    public Optional<Long> findLastWipeAt(@NotNull String worldName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT last_wipe_at FROM rigel_world_state WHERE world_name = ?")) {
            statement.setString(1, worldName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("last_wipe_at")) : Optional.empty();
            }
        }
    }

    public void setLastWipeAt(@NotNull String worldName, long epochMillis) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT 1 FROM rigel_world_state WHERE world_name = ?")) {
                    select.setString(1, worldName);
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE rigel_world_state SET last_wipe_at = ? WHERE world_name = ?"
                        : "INSERT INTO rigel_world_state (last_wipe_at, world_name) VALUES (?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, epochMillis);
                    statement.setString(2, worldName);
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

    /**
     * @return whether {@code worldName} has a restart-based wipe pending on next boot -
     *     see {@code world.FlatlandsService}'s javadoc. Defaults to {@code false} for a
     *     world with no row at all (never scheduled a restart wipe).
     */
    public boolean isPendingWipe(@NotNull String worldName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT pending_wipe FROM rigel_world_state WHERE world_name = ?")) {
            statement.setString(1, worldName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt("pending_wipe") != 0;
            }
        }
    }

    /**
     * Same insert-or-update shape as {@link #setLastWipeAt} - {@code worldName} may not
     * have a row yet either way. {@code last_wipe_at} is {@code NOT NULL} with no default,
     * so the insert branch has to supply <i>something</i> for it too; uses "now" rather
     * than {@code 0} specifically so it doesn't make {@link #findLastWipeAt} look like the
     * world was last wiped at the Unix epoch and trick {@code scheduleAutowipeCycle}'s
     * cadence math into thinking an autowipe is overdue immediately after this call.
     */
    public void setPendingWipe(@NotNull String worldName, boolean pending) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT 1 FROM rigel_world_state WHERE world_name = ?")) {
                    select.setString(1, worldName);
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                if (exists) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE rigel_world_state SET pending_wipe = ? WHERE world_name = ?")) {
                        statement.setInt(1, pending ? 1 : 0);
                        statement.setString(2, worldName);
                        statement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO rigel_world_state (pending_wipe, last_wipe_at, world_name) VALUES (?, ?, ?)")) {
                        statement.setInt(1, pending ? 1 : 0);
                        statement.setLong(2, System.currentTimeMillis());
                        statement.setString(3, worldName);
                        statement.executeUpdate();
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
}
