package org.rigelmc.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.rigelmc.discord.InviteUsesCache.InviteSnapshot;

class InviteUsesCacheTest {

    @Test
    void diffFindsTheCodeWhoseUsesIncreased() {
        InviteUsesCache cache = new InviteUsesCache();
        cache.seed("abc123", 5, "inviter-1");

        Optional<String> used = cache.diffAndFindUsedCode(List.of(new InviteSnapshot("abc123", 6, "inviter-1")));

        assertEquals(Optional.of("abc123"), used);
    }

    @Test
    void diffReturnsEmptyWhenNoCodesUsesChanged() {
        InviteUsesCache cache = new InviteUsesCache();
        cache.seed("abc123", 5, "inviter-1");

        Optional<String> used = cache.diffAndFindUsedCode(List.of(new InviteSnapshot("abc123", 5, "inviter-1")));

        assertTrue(used.isEmpty());
    }

    @Test
    void diffReturnsEmptyForACodeSeenForTheFirstTime() {
        // No previous baseline for a brand-new code seen only in this diff call - a join
        // can't be attributed to something that was never seeded/created, only genuinely
        // increased against a known prior count.
        InviteUsesCache cache = new InviteUsesCache();

        Optional<String> used = cache.diffAndFindUsedCode(List.of(new InviteSnapshot("brandnew", 1, "inviter-1")));

        assertTrue(used.isEmpty());
    }

    @Test
    void diffUpdatesTheBaselineEvenWhenNoCodeIncreased() {
        InviteUsesCache cache = new InviteUsesCache();
        cache.seed("abc123", 5, "inviter-1");
        cache.diffAndFindUsedCode(List.of(new InviteSnapshot("abc123", 5, "inviter-1"))); // no change

        // A later increase from the updated baseline (5) is still detected correctly.
        Optional<String> used = cache.diffAndFindUsedCode(List.of(new InviteSnapshot("abc123", 6, "inviter-1")));

        assertEquals(Optional.of("abc123"), used);
    }

    @Test
    void secondDiffAgainstTheSameUsesCountFindsNothingNew() {
        InviteUsesCache cache = new InviteUsesCache();
        cache.seed("abc123", 5, "inviter-1");
        cache.diffAndFindUsedCode(List.of(new InviteSnapshot("abc123", 6, "inviter-1"))); // first join

        // A second diff against the same (now-baseline) count must not re-report the same join.
        Optional<String> used = cache.diffAndFindUsedCode(List.of(new InviteSnapshot("abc123", 6, "inviter-1")));

        assertTrue(used.isEmpty());
    }

    @Test
    void onInviteDeletedStopsTrackingTheCode() {
        InviteUsesCache cache = new InviteUsesCache();
        cache.seed("abc123", 5, "inviter-1");
        cache.onInviteDeleted("abc123");

        assertTrue(cache.inviterDiscordUserIdFor("abc123").isEmpty());
    }

    @Test
    void aDiffAfterDeletionWithNoPriorBaselineIsNotAttributed() {
        InviteUsesCache cache = new InviteUsesCache();
        cache.seed("abc123", 5, "inviter-1");
        cache.onInviteDeleted("abc123");

        // If a later diff snapshot still includes this code (e.g. it was actually still
        // live on Discord and the delete notification raced with a refetch), there's no
        // prior baseline to compare against right after a delete, so it's correctly not
        // attributed as "used" - it's treated as a rediscovered code, not an increase.
        Optional<String> used = cache.diffAndFindUsedCode(List.of(new InviteSnapshot("abc123", 6, "inviter-1")));

        assertTrue(used.isEmpty());
    }

    @Test
    void onInviteCreatedSeedsANewCode() {
        InviteUsesCache cache = new InviteUsesCache();
        cache.onInviteCreated("fresh", 0, "inviter-2");

        Optional<String> used = cache.diffAndFindUsedCode(List.of(new InviteSnapshot("fresh", 1, "inviter-2")));

        assertEquals(Optional.of("fresh"), used);
        assertEquals(Optional.of("inviter-2"), cache.inviterDiscordUserIdFor("fresh"));
    }

    @Test
    void inviterDiscordUserIdForIsEmptyForAnUnknownCode() {
        InviteUsesCache cache = new InviteUsesCache();
        assertFalse(cache.inviterDiscordUserIdFor("never-seen").isPresent());
    }

    @Test
    void multipleCodesInOneDiffOnlyOneIncreasedIsAttributed() {
        InviteUsesCache cache = new InviteUsesCache();
        cache.seed("code-a", 3, "inviter-a");
        cache.seed("code-b", 7, "inviter-b");

        Optional<String> used = cache.diffAndFindUsedCode(List.of(
                new InviteSnapshot("code-a", 3, "inviter-a"), // unchanged
                new InviteSnapshot("code-b", 8, "inviter-b"))); // increased

        assertEquals(Optional.of("code-b"), used);
    }
}
