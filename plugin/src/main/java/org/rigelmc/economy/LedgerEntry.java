package org.rigelmc.economy;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** One row of {@code rigel_economy_ledger} - see {@link EconomyDao}. */
public record LedgerEntry(
        long id,
        UUID uuid,
        long delta,
        long balanceAfter,
        LedgerReason reason,
        @Nullable String reference,
        @Nullable UUID actorUuid,
        long createdAt) {
}
