package org.rigelmc.economy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thin orchestration over {@link EconomyDao}. Mutating methods are {@code synchronized} -
 * this project's {@code dbExecutor} is a multi-threaded pool, so two adjustments for the
 * same player really can interleave; matches {@code protect.area.ProtectAreaService}'s
 * own {@code create}/{@code update} precedent for the identical reason.
 */
public final class EconomyService {

    private final EconomyDao economyDao;

    public EconomyService(@NotNull EconomyDao economyDao) {
        this.economyDao = economyDao;
    }

    public long balanceOf(@NotNull UUID uuid) throws SQLException {
        return economyDao.findBalance(uuid);
    }

    /** Credits never fail on funds (only a debit can) - still routed through {@link EconomyDao#adjustBalance} for one shared code path. */
    public synchronized long credit(
            @NotNull UUID uuid, long amount, @NotNull LedgerReason reason, @Nullable String reference, @Nullable UUID actorUuid)
            throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("credit amount must be positive: " + amount);
        }
        try {
            return economyDao.adjustBalance(uuid, amount, reason, reference, actorUuid, System.currentTimeMillis());
        } catch (InsufficientFundsException e) {
            // Unreachable: a positive delta can never go negative - see adjustBalance's contract.
            throw new IllegalStateException("credit unexpectedly reported insufficient funds", e);
        }
    }

    public synchronized long debit(
            @NotNull UUID uuid, long amount, @NotNull LedgerReason reason, @Nullable String reference, @Nullable UUID actorUuid)
            throws SQLException, InsufficientFundsException {
        if (amount <= 0) {
            throw new IllegalArgumentException("debit amount must be positive: " + amount);
        }
        return economyDao.adjustBalance(uuid, -amount, reason, reference, actorUuid, System.currentTimeMillis());
    }

    /**
     * Sets a balance to an exact value (positive or negative delta from wherever it
     * currently sits) - used by {@code /economy set}. Never throws {@link
     * InsufficientFundsException}: the caller is declaring the new balance directly, not
     * spending against the old one, so "going negative" doesn't apply the same way (a
     * negative {@code newBalance} is rejected outright instead).
     */
    public synchronized long setBalance(@NotNull UUID uuid, long newBalance, @Nullable UUID actorUuid) throws SQLException {
        if (newBalance < 0) {
            throw new IllegalArgumentException("balance cannot be set negative: " + newBalance);
        }
        long current = economyDao.findBalance(uuid);
        long delta = newBalance - current;
        try {
            return economyDao.adjustBalance(uuid, delta, LedgerReason.ADMIN_SET, null, actorUuid, System.currentTimeMillis());
        } catch (InsufficientFundsException e) {
            // Unreachable: delta is computed so current + delta == newBalance >= 0.
            throw new IllegalStateException("setBalance unexpectedly reported insufficient funds", e);
        }
    }

    /**
     * Moves currency from one player to another as two ledger rows ({@code PAY_SEND} on
     * {@code from}, {@code PAY_RECEIVE} on {@code to}) - not a single atomic JDBC
     * transaction spanning both accounts (each leg is its own {@link
     * EconomyDao#adjustBalance} call), matching this codebase's established precedent of
     * not sharing a connection across separate DAO-level operations (e.g. {@code
     * BanService}'s multi-DAO orchestration). If the debit fails (insufficient funds),
     * the credit never runs - the transfer is simply not attempted, not partially undone.
     *
     * @return {@code false} if {@code from} doesn't have enough (nothing is moved), {@code true} on success
     */
    public synchronized boolean transfer(@NotNull UUID from, @NotNull UUID to, long amount) throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("transfer amount must be positive: " + amount);
        }
        try {
            economyDao.adjustBalance(from, -amount, LedgerReason.PAY_SEND, to.toString(), from, System.currentTimeMillis());
        } catch (InsufficientFundsException e) {
            return false;
        }
        try {
            economyDao.adjustBalance(to, amount, LedgerReason.PAY_RECEIVE, from.toString(), from, System.currentTimeMillis());
        } catch (InsufficientFundsException e) {
            // Unreachable: a positive delta can never go negative.
            throw new IllegalStateException("transfer credit unexpectedly reported insufficient funds", e);
        }
        return true;
    }

    @NotNull
    public List<LedgerEntry> ledgerFor(@NotNull UUID uuid, int limit) throws SQLException {
        return economyDao.findLedgerFor(uuid, limit);
    }

    @NotNull
    public List<BalanceEntry> top(int limit) throws SQLException {
        return economyDao.top(limit);
    }
}
