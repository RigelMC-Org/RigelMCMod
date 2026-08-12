package org.rigelmc.vote;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** One row of {@code rigel_vote_records} - see {@link VoteRecordDao}. */
public record VoteRecord(
        UUID uuid,
        int totalVotes,
        int currentStreak,
        @Nullable Long lastVoteAt,
        long updatedAt) {
}
