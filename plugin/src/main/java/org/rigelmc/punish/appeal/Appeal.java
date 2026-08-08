package org.rigelmc.punish.appeal;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** One row of {@code rigel_ban_appeals} - see docs/architecture.md "Ban appeals". */
public record Appeal(
        long id,
        String banReference,
        long banId,
        String message,
        @Nullable String contact,
        long submittedAt,
        @Nullable String submitterIpHash,
        Status status,
        @Nullable String discordMessageId,
        @Nullable UUID decidedByUuid,
        @Nullable Long decidedAt) {

    public enum Status {
        PENDING,
        APPROVED,
        DENIED
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }
}
