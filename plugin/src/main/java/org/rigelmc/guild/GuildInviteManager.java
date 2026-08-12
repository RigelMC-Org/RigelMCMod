package org.rigelmc.guild;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Pending {@code /guild invite} tracking - deliberately session-only, in-memory, no DAO/
 * table at all, same "resets rather than persists across anything" shape as {@code
 * chat.TagService} (a pending invite isn't meaningful to survive a server restart, and
 * expiring it is a feature, not a limitation - an invite from hours ago shouldn't still be
 * silently acceptable).
 */
public final class GuildInviteManager {

    private static final long TTL_MILLIS = 60_000L;

    /** One pending invite for the invited player - a player can only have one at a time (a new invite overwrites any earlier one). */
    public record PendingInvite(int guildId, String guildName, UUID invitedBy, long expiresAt) {
    }

    private final Map<UUID, PendingInvite> pending = new ConcurrentHashMap<>();

    public void invite(@NotNull UUID targetUuid, int guildId, @NotNull String guildName, @NotNull UUID invitedBy, long now) {
        pending.put(targetUuid, new PendingInvite(guildId, guildName, invitedBy, now + TTL_MILLIS));
    }

    /** @return the target's still-live pending invite, evicting it first if it's expired. */
    @NotNull
    public Optional<PendingInvite> pendingFor(@NotNull UUID targetUuid, long now) {
        PendingInvite invite = pending.get(targetUuid);
        if (invite == null) {
            return Optional.empty();
        }
        if (invite.expiresAt() < now) {
            pending.remove(targetUuid);
            return Optional.empty();
        }
        return Optional.of(invite);
    }

    public void clear(@NotNull UUID targetUuid) {
        pending.remove(targetUuid);
    }
}
