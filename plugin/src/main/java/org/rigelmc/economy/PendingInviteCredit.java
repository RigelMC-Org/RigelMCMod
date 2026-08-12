package org.rigelmc.economy;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** One row of {@code rigel_pending_invite_credits} - see {@link InviteCreditDao}. */
public record PendingInviteCredit(
        int id,
        String discordGuildId,
        String inviteCode,
        String inviterDiscordUserId,
        String invitedDiscordUserId,
        long rewardAmount,
        long joinedAt,
        long eligibleAt,
        InviteCreditStatus status,
        @Nullable Long creditedAt,
        @Nullable UUID creditedUuid) {
}
