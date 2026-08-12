package org.rigelmc.economy;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.discord.DiscordLinkService;

/**
 * Orchestrates {@link InviteCreditDao} + {@link EconomyService} + {@link
 * DiscordLinkService} - the DB-only half of the Discord-invite-tracked economy (see
 * docs/architecture.md "Economy: Discord-invite-tracked currency"). Never touches
 * Discord4J - the diff logic that figures out <i>which</i> invite a join used lives in
 * {@code discord.InviteUsesCache}, called from {@code discord.DiscordBotManager}, which
 * then calls {@link #schedulePendingCredit} here with the already-resolved code/inviter.
 *
 * <p>Resolves the inviter's UUID at <b>award</b> time ({@link #sweep}), not at schedule
 * time - a {@code PENDING} row is keyed by the inviter's Discord user id specifically so an
 * invite that predates the inviter ever linking their Minecraft account still eventually
 * pays out once they do link, no expiry.</p>
 */
public final class InviteCreditService {

    private final InviteCreditDao inviteCreditDao;
    private final EconomyService economyService;
    private final DiscordLinkService discordLinkService;

    public InviteCreditService(
            @NotNull InviteCreditDao inviteCreditDao, @NotNull EconomyService economyService,
            @NotNull DiscordLinkService discordLinkService) {
        this.inviteCreditDao = inviteCreditDao;
        this.economyService = economyService;
        this.discordLinkService = discordLinkService;
    }

    /**
     * Records a new {@code PENDING} credit for a just-attributed join - called from {@code
     * DiscordBotManager}'s {@code MemberJoinEvent} handler once {@code InviteUsesCache} has
     * identified the used invite code and its inviter.
     *
     * @param rewardAmount snapshotted now ({@code economy.invites.reward-amount} at the
     *     time of the join), so a later config change never retroactively alters this
     *     specific pending credit
     * @param eligibleAt {@code joinedAt + economy.invites.min-stay-minutes} - the anti-abuse
     *     minimum-stay window this credit won't be paid out before
     */
    public void schedulePendingCredit(
            @NotNull String discordGuildId, @NotNull String inviteCode, @NotNull String inviterDiscordUserId,
            @NotNull String invitedDiscordUserId, long rewardAmount, long joinedAt, long eligibleAt) throws SQLException {
        inviteCreditDao.insertPending(
                discordGuildId, inviteCode, inviterDiscordUserId, invitedDiscordUserId, rewardAmount, joinedAt, eligibleAt);
    }

    /**
     * Cancels any still-{@code PENDING} credit(s) for this invited Discord user - called
     * from {@code DiscordBotManager}'s {@code MemberLeaveEvent} handler. An already-{@code
     * CREDITED} row is never retroactively punished - only leaving <i>during</i> the
     * probation window kills the reward.
     */
    public void cancelForLeave(@NotNull String invitedDiscordUserId) throws SQLException {
        inviteCreditDao.cancelPendingForInvitedUser(invitedDiscordUserId);
    }

    public record SweepResult(int credited, int stillWaitingOnLink) {
    }

    /**
     * Processes every {@code PENDING} row whose minimum-stay window has elapsed - for each,
     * attempts to resolve the inviter's linked UUID; on success, credits their balance and
     * marks the row {@code CREDITED}, on failure leaves it {@code PENDING} for the next
     * cycle (no expiry - see this class's own javadoc). Called periodically by {@code
     * DiscordModule} at {@code economy.invites.sweep-interval-seconds}.
     */
    @NotNull
    public synchronized SweepResult sweep(long now) throws SQLException {
        List<PendingInviteCredit> due = inviteCreditDao.findDueForSweep(now);
        int credited = 0;
        int stillWaiting = 0;
        for (PendingInviteCredit credit : due) {
            Optional<UUID> inviterUuid = discordLinkService.resolveLinkedUuid(credit.inviterDiscordUserId());
            if (inviterUuid.isEmpty()) {
                stillWaiting++;
                continue;
            }
            economyService.credit(
                    inviterUuid.get(), credit.rewardAmount(), LedgerReason.DISCORD_INVITE, credit.inviteCode(), null);
            inviteCreditDao.markCredited(credit.id(), now, inviterUuid.get());
            credited++;
        }
        return new SweepResult(credited, stillWaiting);
    }
}
