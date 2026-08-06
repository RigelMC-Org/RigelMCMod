package org.rigelmc.webpanel;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable point-in-time snapshot the read-only web panel serves - see {@link
 * WebPanelSnapshotService}. HTTP request handlers only ever read the current one, never
 * query Bukkit or the database directly.
 */
public record WebPanelSnapshot(
        long generatedAt,
        long uptimeMillis,
        double tps,
        int onlineCount,
        int maxPlayers,
        List<PlayerEntry> onlinePlayers,
        List<StaffEntry> staff,
        List<BanEntry> recentBans,
        List<MuteEntry> currentMutes) {

    public record PlayerEntry(String name, String uuid, String rank) {}

    public record StaffEntry(String name, String uuid, String rank, boolean online) {}

    public record BanEntry(
            String type, String target, String reason, long createdAt, @Nullable Long expiresAt, boolean active) {}

    public record MuteEntry(String target, String reason, long createdAt, @Nullable Long expiresAt) {}

    public static WebPanelSnapshot empty() {
        return new WebPanelSnapshot(
                System.currentTimeMillis(), 0, 0.0, 0, 0, List.of(), List.of(), List.of(), List.of());
    }
}
