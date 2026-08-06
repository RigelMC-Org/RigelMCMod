package org.rigelmc.myadmin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_login_messages} - see {@code /myadmin}'s design notes in the migration file. */
public final class LoginMessageDao {

    private final DataSource dataSource;

    public LoginMessageDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    public Optional<String> find(@NotNull UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT message FROM rigel_login_messages WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("message")) : Optional.empty();
            }
        }
    }

    public void upsert(@NotNull UUID uuid, @NotNull String message, long nowEpochMillis) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select =
                        connection.prepareStatement("SELECT 1 FROM rigel_login_messages WHERE uuid = ?")) {
                    select.setString(1, uuid.toString());
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE rigel_login_messages SET message = ?, set_at = ? WHERE uuid = ?"
                        : "INSERT INTO rigel_login_messages (message, set_at, uuid) VALUES (?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, message);
                    statement.setLong(2, nowEpochMillis);
                    statement.setString(3, uuid.toString());
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

    /** @return {@code true} if a message existed and was removed. */
    public boolean delete(@NotNull UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM rigel_login_messages WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            return statement.executeUpdate() > 0;
        }
    }
}
