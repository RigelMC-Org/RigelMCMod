package org.rigelmc.guild;

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
 * Synchronous JDBC access to {@code rigel_guild_members}. A player belongs to at most one
 * guild - enforced by {@code idx_guild_members_uuid} (unique on {@code member_uuid} alone,
 * not just the composite PK) - so {@link #add} is never called speculatively without the
 * caller (see {@code GuildService#create}/{@code #addMember}) already having checked that
 * via {@link #findGuildIdForMember} first.
 */
public final class GuildMemberDao {

    private final DataSource dataSource;

    public GuildMemberDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void add(int guildId, @NotNull UUID memberUuid, @NotNull GuildRole role, long now) throws SQLException {
        String sql = "INSERT INTO rigel_guild_members (guild_id, member_uuid, role, joined_at) VALUES (?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, guildId);
            statement.setString(2, memberUuid.toString());
            statement.setString(3, role.name());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    public void updateRole(int guildId, @NotNull UUID memberUuid, @NotNull GuildRole role) throws SQLException {
        String sql = "UPDATE rigel_guild_members SET role = ? WHERE guild_id = ? AND member_uuid = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, role.name());
            statement.setInt(2, guildId);
            statement.setString(3, memberUuid.toString());
            statement.executeUpdate();
        }
    }

    public void remove(int guildId, @NotNull UUID memberUuid) throws SQLException {
        String sql = "DELETE FROM rigel_guild_members WHERE guild_id = ? AND member_uuid = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, guildId);
            statement.setString(2, memberUuid.toString());
            statement.executeUpdate();
        }
    }

    /** Used by {@code GuildService#disband} - every member row for a disbanding guild, in one statement. */
    public void removeAllForGuild(int guildId) throws SQLException {
        String sql = "DELETE FROM rigel_guild_members WHERE guild_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, guildId);
            statement.executeUpdate();
        }
    }

    @NotNull
    public List<GuildMemberRecord> findForGuild(int guildId) throws SQLException {
        String sql = "SELECT guild_id, member_uuid, role, joined_at FROM rigel_guild_members WHERE guild_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                List<GuildMemberRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        }
    }

    @NotNull
    public Optional<Integer> findGuildIdForMember(@NotNull UUID memberUuid) throws SQLException {
        String sql = "SELECT guild_id FROM rigel_guild_members WHERE member_uuid = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, memberUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getInt("guild_id")) : Optional.empty();
            }
        }
    }

    /** @return every membership row across every guild, loaded once on enable into {@link GuildService}'s cache. */
    @NotNull
    public List<GuildMemberRecord> findAll() throws SQLException {
        String sql = "SELECT guild_id, member_uuid, role, joined_at FROM rigel_guild_members";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<GuildMemberRecord> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        }
    }

    private static GuildMemberRecord mapRow(ResultSet rs) throws SQLException {
        return new GuildMemberRecord(
                rs.getInt("guild_id"),
                UUID.fromString(rs.getString("member_uuid")),
                GuildRole.valueOf(rs.getString("role")),
                rs.getLong("joined_at"));
    }
}
