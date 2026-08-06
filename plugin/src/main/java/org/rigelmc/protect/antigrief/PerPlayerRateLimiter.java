package org.rigelmc.protect.antigrief;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Per-player sliding-window rate limiting, shared by every anti-grief guard that needs "how
 * many of X has this player done in the last N seconds" (block breaks/places, chat
 * messages, commands, item drops, gamemode switches). One instance per limited action per
 * guard - e.g. {@code AntiNukeGuard} owns two (breaks, places).
 */
public final class PerPlayerRateLimiter {

    private final Map<UUID, RateWindow> windows = new ConcurrentHashMap<>();
    private final long windowMillis;

    public PerPlayerRateLimiter(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /** Records one event for this player now and returns the count within the window. */
    public int recordAndCount(@NotNull UUID uuid, long nowMillis) {
        return windows.computeIfAbsent(uuid, key -> new RateWindow(windowMillis)).recordAndCount(nowMillis);
    }

    /** Call on quit - no reason to keep tracking a window for an offline player. */
    public void clear(@NotNull UUID uuid) {
        windows.remove(uuid);
    }
}
