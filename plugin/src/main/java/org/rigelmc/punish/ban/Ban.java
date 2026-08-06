package org.rigelmc.punish.ban;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.api.event.RigelPlayerBannedEvent.BanType;

/**
 * One row of {@code rigel_bans}. A {@code /permban} cascade produces several {@link Ban}
 * instances sharing the same {@link #caseId()} - see docs/architecture.md "Ban system".
 */
public record Ban(
        long id,
        @Nullable String caseId,
        BanType type,
        @Nullable UUID targetUuid,
        @Nullable String targetLastName,
        @Nullable String targetIpHash,
        String reason,
        @Nullable UUID bannedByUuid,
        long createdAt,
        @Nullable Long expiresAt,
        boolean active,
        @Nullable UUID revokedByUuid,
        @Nullable Long revokedAt) {

    public boolean isPermanent() {
        return expiresAt == null;
    }

    public boolean isExpired(long nowEpochMillis) {
        return expiresAt != null && expiresAt <= nowEpochMillis;
    }

    public boolean isCurrentlyInEffect(long nowEpochMillis) {
        return active && !isExpired(nowEpochMillis);
    }
}
