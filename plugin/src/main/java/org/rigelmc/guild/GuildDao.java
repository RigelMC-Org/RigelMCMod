package org.rigelmc.guild;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_guilds}. */
public final class GuildDao {

    private final DataSource dataSource;

    public GuildDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return the new guild's generated id. */
    public int insert(@NotNull String name, @NotNull UUID ownerUuid, long now) throws SQLException {
        String sql = "INSERT INTO rigel_guilds (name, name_lower, owner_uuid, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, name.toLowerCase(Locale.ROOT));
            statement.setString(3, ownerUuid.toString());
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public void setOwner(int guildId, @NotNull UUID newOwner, long now) throws SQLException {
        String sql = "UPDATE rigel_guilds SET owner_uuid = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, newOwner.toString());
            statement.setLong(2, now);
            statement.setInt(3, guildId);
            statement.executeUpdate();
        }
    }

    /** Assigns a plot to this guild - not called until the plot-world sub-phase lands. */
    public void setPlot(int guildId, int plotAreaId, int plotSlotIndex, long now) throws SQLException {
        String sql = "UPDATE rigel_guilds SET plot_area_id = ?, plot_slot_index = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, plotAreaId);
            statement.setInt(2, plotSlotIndex);
            statement.setLong(3, now);
            statement.setInt(4, guildId);
            statement.executeUpdate();
        }
    }

    /** Clears this guild's assigned plot (e.g. if the plot itself is deleted independently of the guild). */
    public void clearPlot(int guildId, long now) throws SQLException {
        String sql = "UPDATE rigel_guilds SET plot_area_id = NULL, plot_slot_index = NULL, updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setInt(2, guildId);
            statement.executeUpdate();
        }
    }

    public void delete(int guildId) throws SQLException {
        String sql = "DELETE FROM rigel_guilds WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, guildId);
            statement.executeUpdate();
        }
    }

    @NotNull
    public Optional<GuildRecord> findByNameLower(@NotNull String nameLower) throws SQLException {
        String sql = "SELECT id, name, name_lower, owner_uuid, plot_area_id, plot_slot_index, created_at, updated_at"
                + " FROM rigel_guilds WHERE name_lower = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nameLower);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    @NotNull
    public Optional<GuildRecord> findById(int id) throws SQLException {
        String sql = "SELECT id, name, name_lower, owner_uuid, plot_area_id, plot_slot_index, created_at, updated_at"
                + " FROM rigel_guilds WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** @return every guild, loaded once on enable into {@link GuildService}'s in-memory cache. */
    @NotNull
    public List<GuildRecord> findAll() throws SQLException {
        String sql = "SELECT id, name, name_lower, owner_uuid, plot_area_id, plot_slot_index, created_at, updated_at"
                + " FROM rigel_guilds ORDER BY name_lower";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<GuildRecord> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        }
    }

    private static GuildRecord mapRow(ResultSet rs) throws SQLException {
        int plotAreaId = rs.getInt("plot_area_id");
        Integer plotAreaIdBoxed = rs.wasNull() ? null : plotAreaId;
        int plotSlotIndex = rs.getInt("plot_slot_index");
        Integer plotSlotIndexBoxed = rs.wasNull() ? null : plotSlotIndex;
        return new GuildRecord(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("name_lower"),
                UUID.fromString(rs.getString("owner_uuid")),
                plotAreaIdBoxed,
                plotSlotIndexBoxed,
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
