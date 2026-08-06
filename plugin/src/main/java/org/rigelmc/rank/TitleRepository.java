package org.rigelmc.rank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_titles} and {@code rigel_player_titles}. */
public final class TitleRepository {

    private final DataSource dataSource;

    public TitleRepository(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    public List<Title> findAll() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT title_id, display_name, prefix FROM rigel_titles");
                ResultSet rs = statement.executeQuery()) {
            List<Title> titles = new ArrayList<>();
            while (rs.next()) {
                titles.add(new Title(rs.getString("title_id"), rs.getString("display_name"), rs.getString("prefix")));
            }
            return titles;
        }
    }

    public void insert(Title title) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO rigel_titles (title_id, display_name, prefix) VALUES (?, ?, ?)")) {
            statement.setString(1, title.id());
            statement.setString(2, title.displayName());
            statement.setString(3, title.prefix());
            statement.executeUpdate();
        }
    }

    public boolean isEmpty() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM rigel_titles LIMIT 1");
                ResultSet rs = statement.executeQuery()) {
            return !rs.next();
        }
    }

    public void grant(UUID uuid, String titleId, UUID grantedBy, long nowEpochMillis) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO rigel_player_titles (uuid, title_id, granted_at, granted_by)"
                                + " VALUES (?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, titleId);
            statement.setLong(3, nowEpochMillis);
            statement.setString(4, grantedBy == null ? null : grantedBy.toString());
            statement.executeUpdate();
        }
    }

    public void revoke(UUID uuid, String titleId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM rigel_player_titles WHERE uuid = ? AND title_id = ?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, titleId);
            statement.executeUpdate();
        }
    }

    @NotNull
    public Set<String> titleIdsFor(UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT title_id FROM rigel_player_titles WHERE uuid = ? ORDER BY granted_at ASC")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                Set<String> result = new LinkedHashSet<>();
                while (rs.next()) {
                    result.add(rs.getString("title_id"));
                }
                return result;
            }
        }
    }
}
