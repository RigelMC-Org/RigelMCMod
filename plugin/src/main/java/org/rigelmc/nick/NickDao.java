package org.rigelmc.nick;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_player_nicks}. */
public final class NickDao {

    private final DataSource dataSource;

    public NickDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void upsert(@NotNull UUID uuid, @NotNull String nickname, long nowEpochMillis) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select =
                        connection.prepareStatement("SELECT 1 FROM rigel_player_nicks WHERE uuid = ?")) {
                    select.setString(1, uuid.toString());
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE rigel_player_nicks SET nickname = ?, set_at = ? WHERE uuid = ?"
                        : "INSERT INTO rigel_player_nicks (nickname, set_at, uuid) VALUES (?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, nickname);
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

    public void delete(@NotNull UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM rigel_player_nicks WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    @NotNull
    public Optional<String> find(@NotNull UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT nickname FROM rigel_player_nicks WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("nickname")) : Optional.empty();
            }
        }
    }
}
