package org.rigelmc.economy;

/**
 * Thrown by {@link EconomyDao#adjustBalance}/{@link EconomyService} when a debit would
 * take a balance negative. A checked exception, not a boolean return, so callers can't
 * accidentally ignore it the way a {@code boolean} return is easy to drop on the floor -
 * every call site must either handle it or declare it, matching this project's existing
 * preference for {@link java.sql.SQLException}-style explicit propagation over silent
 * failure codes.
 */
public final class InsufficientFundsException extends Exception {

    private final long requested;
    private final long available;

    public InsufficientFundsException(long requested, long available) {
        super("Insufficient funds: requested " + requested + ", available " + available);
        this.requested = requested;
        this.available = available;
    }

    public long requested() {
        return requested;
    }

    public long available() {
        return available;
    }
}
