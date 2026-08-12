package org.rigelmc.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Synchronous JDBC access to {@code rigel_economy_accounts}/{@code rigel_economy_ledger}.
 * No in-memory cache here (unlike {@code protect.area.ProtectAreaService}) - balances
 * aren't read on a hot per-tick path, so every call goes straight through, matching
 * {@code punish.ban.BanDao}/{@code punish.mute.MuteDao}'s simpler, uncached style.
 */
public final class EconomyDao {

    private final DataSource dataSource;

    public EconomyDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return the player's current balance, or 0 if they've never held one (no row yet). */
    public long findBalance(@NotNull UUID uuid) throws SQLException {
        String sql = "SELECT balance FROM rigel_economy_accounts WHERE uuid = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong("balance") : 0L;
            }
        }
    }

    /**
     * Applies {@code delta} to {@code uuid}'s balance and records the change, as one JDBC
     * transaction: read-or-create-zero-row, compute the new balance, reject (roll back,
     * throw) a debit that would go negative, upsert the account row, insert the ledger
     * row, commit. The exists-check-then-insert-or-update shape matches {@code
     * protect.area.AreaMemberDao#add}'s own portable (SQLite/MySQL) upsert convention -
     * deliberately not {@code INSERT OR IGNORE}/{@code ON CONFLICT}, which the two
     * dialects spell differently.
     *
     * @param delta positive to credit, negative to debit
     * @param actorUuid who/what caused this change, or {@code null} for a system-issued
     *     one (e.g. a Discord-invite reward has no in-game actor)
     * @return the balance after this change
     * @throws InsufficientFundsException if {@code delta} is negative and would take the
     *     balance below zero - the transaction is rolled back, nothing is written
     */
    public long adjustBalance(
            @NotNull UUID uuid,
            long delta,
            @NotNull LedgerReason reason,
            @Nullable String reference,
            @Nullable UUID actorUuid,
            long now)
            throws SQLException, InsufficientFundsException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long current = currentBalance(connection, uuid);
                long updated = current + delta;
                if (updated < 0) {
                    connection.rollback();
                    throw new InsufficientFundsException(-delta, current);
                }
                upsertAccount(connection, uuid, updated, now);
                insertLedgerRow(connection, uuid, delta, updated, reason, reference, actorUuid, now);
                connection.commit();
                return updated;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private long currentBalance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT balance FROM rigel_economy_accounts WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getLong("balance") : 0L;
            }
        }
    }

    private void upsertAccount(Connection connection, UUID uuid, long newBalance, long now) throws SQLException {
        boolean exists;
        try (PreparedStatement select =
                connection.prepareStatement("SELECT 1 FROM rigel_economy_accounts WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet rs = select.executeQuery()) {
                exists = rs.next();
            }
        }
        if (exists) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE rigel_economy_accounts SET balance = ?, updated_at = ? WHERE uuid = ?")) {
                update.setLong(1, newBalance);
                update.setLong(2, now);
                update.setString(3, uuid.toString());
                update.executeUpdate();
            }
        } else {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO rigel_economy_accounts (uuid, balance, updated_at) VALUES (?, ?, ?)")) {
                insert.setString(1, uuid.toString());
                insert.setLong(2, newBalance);
                insert.setLong(3, now);
                insert.executeUpdate();
            }
        }
    }

    private void insertLedgerRow(
            Connection connection,
            UUID uuid,
            long delta,
            long balanceAfter,
            LedgerReason reason,
            @Nullable String reference,
            @Nullable UUID actorUuid,
            long now)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rigel_economy_ledger"
                        + " (uuid, delta, balance_after, reason, reference, actor_uuid, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, uuid.toString());
            insert.setLong(2, delta);
            insert.setLong(3, balanceAfter);
            insert.setString(4, reason.name());
            if (reference != null) {
                insert.setString(5, reference);
            } else {
                insert.setNull(5, Types.VARCHAR);
            }
            if (actorUuid != null) {
                insert.setString(6, actorUuid.toString());
            } else {
                insert.setNull(6, Types.VARCHAR);
            }
            insert.setLong(7, now);
            insert.executeUpdate();
        }
    }

    /** @return this player's most recent ledger entries, newest first, capped at {@code limit}. */
    @NotNull
    public List<LedgerEntry> findLedgerFor(@NotNull UUID uuid, int limit) throws SQLException {
        String sql = "SELECT id, uuid, delta, balance_after, reason, reference, actor_uuid, created_at"
                + " FROM rigel_economy_ledger WHERE uuid = ? ORDER BY id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<LedgerEntry> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapLedgerRow(rs));
                }
                return result;
            }
        }
    }

    /** @return the highest {@code limit} balances, descending - for {@code /economy top}. */
    @NotNull
    public List<BalanceEntry> top(int limit) throws SQLException {
        String sql = "SELECT uuid, balance FROM rigel_economy_accounts ORDER BY balance DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<BalanceEntry> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new BalanceEntry(UUID.fromString(rs.getString("uuid")), rs.getLong("balance")));
                }
                return result;
            }
        }
    }

    private static LedgerEntry mapLedgerRow(ResultSet rs) throws SQLException {
        String reference = rs.getString("reference");
        String actorUuidRaw = rs.getString("actor_uuid");
        return new LedgerEntry(
                rs.getLong("id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getLong("delta"),
                rs.getLong("balance_after"),
                LedgerReason.valueOf(rs.getString("reason")),
                reference,
                actorUuidRaw != null ? UUID.fromString(actorUuidRaw) : null,
                rs.getLong("created_at"));
    }
}
