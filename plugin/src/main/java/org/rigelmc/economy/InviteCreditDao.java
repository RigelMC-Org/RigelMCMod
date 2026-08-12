package org.rigelmc.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_pending_invite_credits} - see {@link PendingInviteCredit}. */
public final class InviteCreditDao {

    private final DataSource dataSource;

    public InviteCreditDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return the newly-inserted row's generated id. */
    public int insertPending(
            @NotNull String discordGuildId, @NotNull String inviteCode, @NotNull String inviterDiscordUserId,
            @NotNull String invitedDiscordUserId, long rewardAmount, long joinedAt, long eligibleAt) throws SQLException {
        String sql = "INSERT INTO rigel_pending_invite_credits"
                + " (discord_guild_id, invite_code, inviter_discord_user_id, invited_discord_user_id,"
                + " reward_amount, joined_at, eligible_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, discordGuildId);
            statement.setString(2, inviteCode);
            statement.setString(3, inviterDiscordUserId);
            statement.setString(4, invitedDiscordUserId);
            statement.setLong(5, rewardAmount);
            statement.setLong(6, joinedAt);
            statement.setLong(7, eligibleAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Insert into rigel_pending_invite_credits returned no generated key");
                }
                return keys.getInt(1);
            }
        }
    }

    /** @return every {@code PENDING} row whose {@code eligible_at} has already passed - what {@link InviteCreditService#sweep} processes each cycle. */
    @NotNull
    public List<PendingInviteCredit> findDueForSweep(long now) throws SQLException {
        String sql = "SELECT * FROM rigel_pending_invite_credits WHERE status = 'PENDING' AND eligible_at <= ? ORDER BY id";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            try (ResultSet rs = statement.executeQuery()) {
                List<PendingInviteCredit> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        }
    }

    public void markCredited(int id, long creditedAt, @NotNull UUID creditedUuid) throws SQLException {
        String sql = "UPDATE rigel_pending_invite_credits SET status = 'CREDITED', credited_at = ?,"
                + " credited_uuid = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, creditedAt);
            statement.setString(2, creditedUuid.toString());
            statement.setInt(3, id);
            statement.executeUpdate();
        }
    }

    /**
     * Flips every still-{@code PENDING} row for this invited Discord user to {@code
     * CANCELLED} - see {@link InviteCreditStatus#CANCELLED}'s javadoc for why an already-
     * {@code CREDITED} row is untouched (the {@code status = 'PENDING'} guard here).
     *
     * @return how many rows were cancelled (normally 0 or 1 - more only if the same user
     *     joined, left uncredited, and rejoined multiple times before ever being credited)
     */
    public int cancelPendingForInvitedUser(@NotNull String invitedDiscordUserId) throws SQLException {
        String sql = "UPDATE rigel_pending_invite_credits SET status = 'CANCELLED'"
                + " WHERE invited_discord_user_id = ? AND status = 'PENDING'";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, invitedDiscordUserId);
            return statement.executeUpdate();
        }
    }

    private static PendingInviteCredit mapRow(ResultSet rs) throws SQLException {
        long creditedAtRaw = rs.getLong("credited_at");
        Long creditedAt = rs.wasNull() ? null : creditedAtRaw;
        String creditedUuidRaw = rs.getString("credited_uuid");
        UUID creditedUuid = creditedUuidRaw != null ? UUID.fromString(creditedUuidRaw) : null;
        return new PendingInviteCredit(
                rs.getInt("id"),
                rs.getString("discord_guild_id"),
                rs.getString("invite_code"),
                rs.getString("inviter_discord_user_id"),
                rs.getString("invited_discord_user_id"),
                rs.getLong("reward_amount"),
                rs.getLong("joined_at"),
                rs.getLong("eligible_at"),
                InviteCreditStatus.valueOf(rs.getString("status")),
                creditedAt,
                creditedUuid);
    }
}
