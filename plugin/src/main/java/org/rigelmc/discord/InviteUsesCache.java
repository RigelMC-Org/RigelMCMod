package org.rigelmc.discord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pure, unit-testable diff logic for attributing a Discord guild join to the invite code
 * that was used - Discord's gateway genuinely doesn't expose this directly (confirmed via
 * {@code javap} against the real Discord4J 3.3.2 jar: {@code MemberJoinEvent} carries no
 * invite-code field at all), so the only reliable approach is tracking every invite's use
 * count ourselves and diffing against a fresh snapshot whenever someone joins.
 *
 * <p>Seeded from {@code Guild.getInvites()} once on connect, kept live afterward via {@code
 * InviteCreateEvent}/{@code InviteDeleteEvent} (both carry {@code uses}/{@code code}
 * directly, no extra REST call needed for those two). On a join, {@code
 * DiscordBotManager} refetches the guild's current invites and calls {@link
 * #diffAndFindUsedCode} - whichever code's use count increased since the last known value
 * is the one that was used. No Discord4J/Bukkit types here at all, only plain records -
 * that's what keeps this fully testable without a live gateway (see {@code
 * InviteUsesCacheTest}).</p>
 *
 * <p><b>Not thread-safety-complete on its own</b>: {@link #diffAndFindUsedCode} is {@code
 * synchronized} so two overlapping calls never interleave their own read-then-write, but
 * the REST refetch that produces its input happens <i>outside</i> this class, in {@code
 * DiscordBotManager}. Two near-simultaneous joins can each refetch before either has
 * diffed/updated the cache, both seeing the same stale baseline - {@code
 * DiscordBotManager} closes that race with its own {@code ReentrantLock} wrapping the whole
 * refetch-then-diff sequence per join, not just the diff call in isolation.</p>
 */
public final class InviteUsesCache {

    private final Map<String, Integer> usesByCode = new ConcurrentHashMap<>();
    private final Map<String, String> inviterDiscordUserIdByCode = new ConcurrentHashMap<>();

    /** One invite's current state, as reported by a REST call ({@code Guild.getInvites()}) or a create/delete gateway event. */
    public record InviteSnapshot(@NotNull String code, int uses, @Nullable String inviterDiscordUserId) {
    }

    /** Seeds (or overwrites) one invite's known state - used on initial connect and by {@link #onInviteCreated}. */
    public void seed(@NotNull String code, int uses, @Nullable String inviterDiscordUserId) {
        usesByCode.put(code, uses);
        if (inviterDiscordUserId != null) {
            inviterDiscordUserIdByCode.put(code, inviterDiscordUserId);
        }
    }

    /** A new invite was created - {@code InviteCreateEvent} already carries {@code uses}/{@code code}/{@code inviter}, no refetch needed. */
    public void onInviteCreated(@NotNull String code, int uses, @Nullable String inviterDiscordUserId) {
        seed(code, uses, inviterDiscordUserId);
    }

    /** An invite was deleted or expired - stop tracking it so a later diff never attributes a join to a now-dead code. */
    public void onInviteDeleted(@NotNull String code) {
        usesByCode.remove(code);
        inviterDiscordUserIdByCode.remove(code);
    }

    /**
     * Diffs a freshly-fetched snapshot of every invite against the last known use counts,
     * returning whichever code's count increased - the join's likely source. Updates the
     * cache to the new counts for every snapshot passed in regardless of whether a match
     * was found, so the next call diffs against the right baseline.
     *
     * @return the used invite's code, or empty if none increased (e.g. the join came from
     *     the guild's discovery page or a vanity URL, neither of which is a trackable
     *     invite code at all)
     */
    @NotNull
    public synchronized Optional<String> diffAndFindUsedCode(@NotNull List<InviteSnapshot> currentInvites) {
        String usedCode = null;
        for (InviteSnapshot snapshot : currentInvites) {
            Integer previous = usesByCode.get(snapshot.code());
            if (previous != null && snapshot.uses() > previous) {
                usedCode = snapshot.code();
            }
            usesByCode.put(snapshot.code(), snapshot.uses());
            if (snapshot.inviterDiscordUserId() != null) {
                inviterDiscordUserIdByCode.put(snapshot.code(), snapshot.inviterDiscordUserId());
            }
        }
        return Optional.ofNullable(usedCode);
    }

    @NotNull
    public Optional<String> inviterDiscordUserIdFor(@NotNull String code) {
        return Optional.ofNullable(inviterDiscordUserIdByCode.get(code));
    }
}
