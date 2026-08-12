package org.rigelmc.economy;

import java.util.UUID;

/** One row of {@code /economy top} - a player and their current balance. See {@link EconomyDao#top}. */
public record BalanceEntry(UUID uuid, long balance) {
}
