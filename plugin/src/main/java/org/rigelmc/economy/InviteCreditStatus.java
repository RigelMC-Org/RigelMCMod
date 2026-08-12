package org.rigelmc.economy;

/** Fixed vocabulary for {@code rigel_pending_invite_credits.status} - see {@link InviteCreditService}. */
public enum InviteCreditStatus {
    /** Scheduled on join, waiting for the minimum-stay window to elapse and the inviter's account to be linked. */
    PENDING,
    /** Paid out - {@link InviteCreditService#sweep} both resolved the inviter's UUID and found the row past {@code eligible_at}. */
    CREDITED,
    /** The invited member left before {@code eligible_at} - see {@link InviteCreditService#cancelForLeave}. Never applied to an already-{@link #CREDITED} row. */
    CANCELLED
}
