package org.rigelmc.rank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_ranks} and {@code rigel_rank_permissions}. */
public final class RankRepository {

    private final DataSource dataSource;

    public RankRepository(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    public List<Rank> findAll() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT rank_id, display_name, prefix, weight, is_default FROM rigel_ranks"
                                + " ORDER BY weight ASC");
                ResultSet rs = statement.executeQuery()) {
            List<Rank> ranks = new ArrayList<>();
            while (rs.next()) {
                ranks.add(new Rank(
                        rs.getString("rank_id"),
                        rs.getString("display_name"),
                        rs.getString("prefix"),
                        rs.getInt("weight"),
                        rs.getInt("is_default") != 0));
            }
            return ranks;
        }
    }

    public void insert(Rank rank) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO rigel_ranks (rank_id, display_name, prefix, weight, is_default)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, rank.id());
            statement.setString(2, rank.displayName());
            statement.setString(3, rank.prefix());
            statement.setInt(4, rank.weight());
            statement.setInt(5, rank.isDefault() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    public boolean isEmpty() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM rigel_ranks LIMIT 1");
                ResultSet rs = statement.executeQuery()) {
            return !rs.next();
        }
    }
}
