package org.rigelmc.economy;

/**
 * Fixed vocabulary for {@code rigel_economy_ledger.reason} - deliberately a small closed
 * enum (matching {@code protect.area.AreaFlag}'s own "small fixed set, not an open-ended
 * registry" precedent) rather than free-text, so a future {@code /economy history} could
 * filter by it cleanly. Stored as {@link #name()} (the enum constant's own name), read back
 * via {@link #valueOf(String)}.
 */
public enum LedgerReason {

    /** Discord-invite reward, credited by {@code economy.InviteCreditService}. */
    DISCORD_INVITE,
    /** The sending side of a {@code /pay}. */
    PAY_SEND,
    /** The receiving side of a {@code /pay}. */
    PAY_RECEIVE,
    /** {@code /economy give}. */
    ADMIN_GIVE,
    /** {@code /economy take}. */
    ADMIN_TAKE,
    /** {@code /economy set}. */
    ADMIN_SET,
    /** A guild plot cosmetic purchase - {@code guild.plot.PlotCosmeticService}. */
    PLOT_COSMETIC,
    /** A Tebex (or any external store) purchase, granted via {@code /store grant coins} - {@code store.StoreModule}. */
    STORE_PURCHASE,
    /** A vote-site reward or streak/milestone bonus, granted via {@code /vote record} - {@code vote.VoteRecordService}. */
    VOTE_REWARD
}
