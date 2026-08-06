package org.rigelmc.protect.antigrief;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A single-key sliding-time-window event counter - e.g. "how many blocks has one player
 * broken in the last second". Not thread-safe by itself; every caller in this package runs
 * on synchronous Bukkit events on the main thread, so no synchronization is needed here -
 * {@link PerPlayerRateLimiter} is what adds the concurrency-safe per-player map on top.
 */
final class RateWindow {

    private final Deque<Long> timestamps = new ArrayDeque<>();
    private final long windowMillis;

    RateWindow(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /** Records one event now and returns the count within the window, including this one. */
    int recordAndCount(long nowMillis) {
        timestamps.addLast(nowMillis);
        prune(nowMillis);
        return timestamps.size();
    }

    private void prune(long nowMillis) {
        while (!timestamps.isEmpty() && nowMillis - timestamps.peekFirst() > windowMillis) {
            timestamps.pollFirst();
        }
    }
}
