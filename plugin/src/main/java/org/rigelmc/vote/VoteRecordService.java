package org.rigelmc.vote;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.economy.EconomyService;
import org.rigelmc.economy.LedgerReason;
import org.rigelmc.rank.TitleService;

/**
 * Orchestrates {@link VoteRecordDao} + {@link EconomyService} + {@link TitleService} - the
 * streak/milestone reward logic behind {@code /vote record <player>}. Pure JDBC/DB
 * orchestration, no Bukkit/Discord dependency, so it's unit-testable against a real
 * temp-file SQLite database, no MockBukkit.
 *
 * <p><b>Streak</b>: a vote within {@code streakWindow} of the player's last one continues
 * their streak (increments by 1); anything longer resets it to 1. <b>Streak bonuses</b> are
 * awarded every time the streak reaches an exact configured length - streaks reset and can
 * be re-earned, so these deliberately repeat, unlike {@code punish.strikes.thresholds}'
 * high-water-mark semantics. <b>Milestone bonuses/titles</b> key off cumulative total votes
 * instead, which only ever increases - checking for an exact match is naturally one-time per
 * value with no separate "already awarded" bookkeeping needed.</p>
 */
public final class VoteRecordService {

    private final VoteRecordDao voteRecordDao;
    private final EconomyService economyService;
    private final TitleService titleService;

    public VoteRecordService(
            @NotNull VoteRecordDao voteRecordDao, @NotNull EconomyService economyService, @NotNull TitleService titleService) {
        this.voteRecordDao = voteRecordDao;
        this.economyService = economyService;
        this.titleService = titleService;
    }

    /** What a single {@link #recordVote} call actually granted - for the console reply. */
    public record VoteOutcome(
            int totalVotes,
            int currentStreak,
            long baseReward,
            @Nullable Long streakBonus,
            @Nullable Long milestoneBonus,
            @Nullable String titleGranted) {
    }

    /**
     * Records one vote for {@code uuid}, updates their streak/total, and grants whatever
     * rewards the current config maps to. Never throws on an unconfigured/zero reward - a
     * zero {@code rewardPerVote}, or a streak/total with no matching bonus entry, simply
     * grants nothing for that piece (checked explicitly rather than relying on {@link
     * EconomyService#credit}'s own positive-amount requirement, since a literal 0 is a valid
     * "base reward disabled" config choice, not a caller bug).
     */
    @NotNull
    public synchronized VoteOutcome recordVote(
            @NotNull UUID uuid, long now, long rewardPerVote, @NotNull Duration streakWindow,
            @NotNull Map<Integer, Long> streakBonuses, @NotNull Map<Integer, Long> milestoneBonuses,
            @NotNull Map<Integer, String> milestoneTitles) throws SQLException {
        Optional<VoteRecord> existing = voteRecordDao.find(uuid);
        int previousStreak = existing.map(VoteRecord::currentStreak).orElse(0);
        Long lastVoteAt = existing.map(VoteRecord::lastVoteAt).orElse(null);
        int previousTotal = existing.map(VoteRecord::totalVotes).orElse(0);

        int newStreak = (lastVoteAt != null && (now - lastVoteAt) <= streakWindow.toMillis()) ? previousStreak + 1 : 1;
        int newTotal = previousTotal + 1;
        voteRecordDao.upsert(new VoteRecord(uuid, newTotal, newStreak, now, now));

        if (rewardPerVote > 0) {
            economyService.credit(uuid, rewardPerVote, LedgerReason.VOTE_REWARD, "vote", null);
        }

        Long streakBonus = streakBonuses.get(newStreak);
        if (streakBonus != null && streakBonus > 0) {
            economyService.credit(uuid, streakBonus, LedgerReason.VOTE_REWARD, "vote-streak-" + newStreak, null);
        }

        Long milestoneBonus = milestoneBonuses.get(newTotal);
        if (milestoneBonus != null && milestoneBonus > 0) {
            economyService.credit(uuid, milestoneBonus, LedgerReason.VOTE_REWARD, "vote-milestone-" + newTotal, null);
        }

        String titleId = milestoneTitles.get(newTotal);
        String titleGranted = null;
        if (titleId != null && titleService.title(titleId).isPresent()) {
            titleService.ensureGranted(uuid, titleId, null, now);
            titleGranted = titleId;
        }

        return new VoteOutcome(newTotal, newStreak, rewardPerVote, streakBonus, milestoneBonus, titleGranted);
    }
}
