package org.rigelmc.vote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;
import org.rigelmc.economy.EconomyDao;
import org.rigelmc.economy.EconomyService;
import org.rigelmc.rank.TitleRepository;
import org.rigelmc.rank.TitleService;

/**
 * Pure-JDBC, no MockBukkit - {@link VoteRecordService} only touches {@link VoteRecordDao},
 * {@link EconomyService}, and {@link TitleService}, all of which run against a real
 * temp-file SQLite database here, matching {@code InviteCreditServiceTest}'s precedent.
 */
class VoteRecordServiceTest {

    private static final Duration WINDOW = Duration.ofHours(48);
    private static final Map<Integer, Long> NO_STREAK_BONUSES = Map.of();
    private static final Map<Integer, Long> NO_MILESTONE_BONUSES = Map.of();
    private static final Map<Integer, String> NO_MILESTONE_TITLES = Map.of();

    private HikariDataSource dataSource;
    private EconomyService economyService;
    private TitleService titleService;
    private VoteRecordService voteRecordService;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.economyService = new EconomyService(new EconomyDao(dataSource));
        this.titleService = new TitleService(new TitleRepository(dataSource));
        titleService.initialize();
        this.voteRecordService = new VoteRecordService(new VoteRecordDao(dataSource), economyService, titleService);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void firstVoteStartsAStreakOfOneAndCreditsTheBaseReward() throws Exception {
        UUID uuid = UUID.randomUUID();

        VoteRecordService.VoteOutcome outcome = voteRecordService.recordVote(
                uuid, 1000L, 50, WINDOW, NO_STREAK_BONUSES, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);

        assertEquals(1, outcome.totalVotes());
        assertEquals(1, outcome.currentStreak());
        assertEquals(50L, outcome.baseReward());
        assertEquals(50L, economyService.balanceOf(uuid));
    }

    @Test
    void zeroRewardPerVoteGrantsNoBaseReward() throws Exception {
        UUID uuid = UUID.randomUUID();

        voteRecordService.recordVote(uuid, 1000L, 0, WINDOW, NO_STREAK_BONUSES, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);

        assertEquals(0L, economyService.balanceOf(uuid));
    }

    @Test
    void aVoteWithinTheStreakWindowContinuesTheStreak() throws Exception {
        UUID uuid = UUID.randomUUID();
        voteRecordService.recordVote(uuid, 1000L, 0, WINDOW, NO_STREAK_BONUSES, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);

        VoteRecordService.VoteOutcome outcome = voteRecordService.recordVote(
                uuid, 1000L + WINDOW.toMillis(), 0, WINDOW, NO_STREAK_BONUSES, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);

        assertEquals(2, outcome.currentStreak());
        assertEquals(2, outcome.totalVotes());
    }

    @Test
    void aVoteAfterTheStreakWindowResetsTheStreakButNotTheTotal() throws Exception {
        UUID uuid = UUID.randomUUID();
        voteRecordService.recordVote(uuid, 1000L, 0, WINDOW, NO_STREAK_BONUSES, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);

        VoteRecordService.VoteOutcome outcome = voteRecordService.recordVote(
                uuid, 1000L + WINDOW.toMillis() + 1, 0, WINDOW, NO_STREAK_BONUSES, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);

        assertEquals(1, outcome.currentStreak()); // reset
        assertEquals(2, outcome.totalVotes()); // total keeps growing regardless
    }

    @Test
    void streakBonusIsAwardedOnTheExactConfiguredStreakLength() throws Exception {
        UUID uuid = UUID.randomUUID();
        Map<Integer, Long> streakBonuses = Map.of(3, 100L);
        long now = 1000L;
        voteRecordService.recordVote(uuid, now, 0, WINDOW, streakBonuses, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);
        now += 1000L;
        voteRecordService.recordVote(uuid, now, 0, WINDOW, streakBonuses, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);
        now += 1000L;

        VoteRecordService.VoteOutcome outcome = voteRecordService.recordVote(
                uuid, now, 0, WINDOW, streakBonuses, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);

        assertEquals(3, outcome.currentStreak());
        assertEquals(100L, outcome.streakBonus());
        assertEquals(100L, economyService.balanceOf(uuid));
    }

    @Test
    void streakBonusesRepeatEachTimeTheStreakIsReEarned() throws Exception {
        UUID uuid = UUID.randomUUID();
        Map<Integer, Long> streakBonuses = Map.of(1, 10L);
        long now = 1000L;
        voteRecordService.recordVote(uuid, now, 0, WINDOW, streakBonuses, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);
        assertEquals(10L, economyService.balanceOf(uuid));

        // Break the streak, then re-earn streak length 1 again.
        now += WINDOW.toMillis() + 1;
        voteRecordService.recordVote(uuid, now, 0, WINDOW, streakBonuses, NO_MILESTONE_BONUSES, NO_MILESTONE_TITLES);

        assertEquals(20L, economyService.balanceOf(uuid)); // bonus paid a second time
    }

    @Test
    void milestoneBonusIsAwardedOnceWhenTotalVotesFirstReachesIt() throws Exception {
        UUID uuid = UUID.randomUUID();
        Map<Integer, Long> milestoneBonuses = Map.of(2, 500L);
        long now = 1000L;
        voteRecordService.recordVote(uuid, now, 0, WINDOW, NO_STREAK_BONUSES, milestoneBonuses, NO_MILESTONE_TITLES);
        now += WINDOW.toMillis() + 1; // break the streak so it doesn't interfere; total keeps climbing regardless

        VoteRecordService.VoteOutcome outcome = voteRecordService.recordVote(
                uuid, now, 0, WINDOW, NO_STREAK_BONUSES, milestoneBonuses, NO_MILESTONE_TITLES);

        assertEquals(2, outcome.totalVotes());
        assertEquals(500L, outcome.milestoneBonus());
        assertEquals(500L, economyService.balanceOf(uuid));

        now += WINDOW.toMillis() + 1;
        VoteRecordService.VoteOutcome thirdVote = voteRecordService.recordVote(
                uuid, now, 0, WINDOW, NO_STREAK_BONUSES, milestoneBonuses, NO_MILESTONE_TITLES);

        assertNull(thirdVote.milestoneBonus()); // total is now 3 - no longer matches the milestone entry
        assertEquals(500L, economyService.balanceOf(uuid)); // unchanged
    }

    @Test
    void milestoneTitleIsGrantedWhenTotalVotesMatchesAndTheTitleExists() throws Exception {
        UUID uuid = UUID.randomUUID();
        assertTrue(titleService.title("voter").isPresent()); // seeded by Title.defaultTitles()
        Map<Integer, String> milestoneTitles = Map.of(1, "voter");

        VoteRecordService.VoteOutcome outcome = voteRecordService.recordVote(
                uuid, 1000L, 0, WINDOW, NO_STREAK_BONUSES, NO_MILESTONE_BONUSES, milestoneTitles);

        assertEquals("voter", outcome.titleGranted());
        Set<String> titleIds = titleService.titleIdsFor(uuid);
        assertTrue(titleIds.contains("voter"));
    }

    @Test
    void milestoneTitleIsSkippedWhenTheConfiguredTitleIdIsUnknown() throws Exception {
        UUID uuid = UUID.randomUUID();
        Map<Integer, String> milestoneTitles = Map.of(1, "not-a-real-title");

        VoteRecordService.VoteOutcome outcome = voteRecordService.recordVote(
                uuid, 1000L, 0, WINDOW, NO_STREAK_BONUSES, NO_MILESTONE_BONUSES, milestoneTitles);

        assertNull(outcome.titleGranted());
    }
}
