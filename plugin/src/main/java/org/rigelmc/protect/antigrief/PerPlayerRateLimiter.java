package org.rigelmc.protect.antigrief;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Per-key sliding-window rate limiting, shared by every guard that needs "how many of X
 * has this key done in the last N seconds" (block breaks/places, chat messages, commands,
 * item drops, gamemode switches - all keyed by player {@code UUID}; also used keyed by
 * submitter IP-hash {@code String} for the public ban-appeal form, which has no player
 * identity to key by at all). One instance per limited action per guard - e.g. {@code
 * AntiNukeGuard} owns two (breaks, places). Generic over the key type {@code K} rather than
 * hardcoded to {@code UUID} specifically for that IP-hash use - every existing caller keeps
 * working unchanged, just spelled out as {@code PerPlayerRateLimiter<UUID>} now.
 */
public final class PerPlayerRateLimiter<K> {

    private final Map<K, RateWindow> windows = new ConcurrentHashMap<>();
    private final long windowMillis;

    public PerPlayerRateLimiter(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /** Records one event for this key now and returns the count within the window. */
    public int recordAndCount(@NotNull K key, long nowMillis) {
        return windows.computeIfAbsent(key, k -> new RateWindow(windowMillis)).recordAndCount(nowMillis);
    }

    /** Call on quit (or, for a non-player key, whenever the window is no longer worth tracking). */
    public void clear(@NotNull K key) {
        windows.remove(key);
    }
}
