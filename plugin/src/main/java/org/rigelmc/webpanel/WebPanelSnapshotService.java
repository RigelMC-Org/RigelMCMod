package org.rigelmc.webpanel;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;
import org.rigelmc.punish.ban.Ban;
import org.rigelmc.punish.ban.BanDao;
import org.rigelmc.punish.mute.MuteDao;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.Rank;
import org.rigelmc.rank.Title;
import org.rigelmc.rank.TitleService;

/**
 * Periodically rebuilds the immutable {@link WebPanelSnapshot} the HTTP server's request
 * handlers serve - never queried live per-request, so a request-handling thread (on its
 * own dedicated executor, see {@link WebPanelServer}) never touches Bukkit API or the
 * database directly. DB reads happen off the main thread (this plugin's existing shared
 * {@code dbExecutor}); the brief Bukkit-API-touching part (online players, TPS) hops back
 * to the main thread via a short callback, matching every other DB-then-Bukkit pattern
 * already established elsewhere in this codebase (e.g. {@code rank.RankAdminModule}).
 */
public final class WebPanelSnapshotService {

    private final RigelMCMod plugin;
    private final PlayerDao playerDao;
    private final BanDao banDao;
    private final MuteDao muteDao;
    private final PermissionGate permissionGate;
    private final TitleService titleService;
    private final ExecutorService dbExecutor;
    private final long startedAt = System.currentTimeMillis();
    private volatile WebPanelSnapshot snapshot = WebPanelSnapshot.empty();

    public WebPanelSnapshotService(
            @NotNull RigelMCMod plugin,
            @NotNull PlayerDao playerDao,
            @NotNull BanDao banDao,
            @NotNull MuteDao muteDao,
            @NotNull PermissionGate permissionGate,
            @NotNull TitleService titleService,
            @NotNull ExecutorService dbExecutor) {
        this.plugin = plugin;
        this.playerDao = playerDao;
        this.banDao = banDao;
        this.muteDao = muteDao;
        this.permissionGate = permissionGate;
        this.titleService = titleService;
        this.dbExecutor = dbExecutor;
    }

    @NotNull
    public WebPanelSnapshot current() {
        return snapshot;
    }

    /** Kicks off one refresh cycle - safe to call from any thread, does its own dispatch. */
    public void refresh() {
        dbExecutor.submit(() -> {
            try {
                List<PlayerRecord> ranked = playerDao.findAllRanked();
                List<Ban> recentBans = banDao.findRecent(20);
                List<MuteDao.MuteRecord> mutes = muteDao.findAll();

                // Title lookups happen here (off the main thread, JDBC-backed) rather than
                // in applyRefresh - titles are only ever meaningfully held by a ranked
                // player (see rank.Title's own "worn by Senior Admins" javadoc), so keying
                // this off the same `ranked` list already covers every online player too
                // without a second main-thread hop just to enumerate live UUIDs first.
                Map<UUID, String> titleLabels = new HashMap<>();
                for (PlayerRecord record : ranked) {
                    String label = resolveTitleLabel(record.uuid());
                    if (label != null) {
                        titleLabels.put(record.uuid(), label);
                    }
                }

                Bukkit.getScheduler().runTask(plugin, () -> applyRefresh(ranked, recentBans, mutes, titleLabels));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to refresh web panel snapshot", e);
            }
        });
    }

    /**
     * @return the display name of the highest-{@link Title#weight}-holding title this
     *     player currently holds, or {@code null} if they hold none (caller falls back to
     *     the rank's own display name). Simplified relative to {@code
     *     rank.PrefixService#refresh}'s fully general version (which also compares against
     *     the player's actual resolved {@link Rank} weight) - every built-in title's
     *     weight (40+) already exceeds every built-in rank's weight (30 max), so for this
     *     project's real rank ladder "holds any title" and "title outranks their rank"
     *     are the same condition in practice. A custom rank configured above weight 40
     *     would need the fuller comparison; flagged here rather than silently assumed.
     */
    @Nullable
    private String resolveTitleLabel(UUID uuid) throws SQLException {
        Set<String> titleIds = titleService.titleIdsFor(uuid);
        if (titleIds.isEmpty()) {
            return null;
        }
        String bestId = null;
        int bestWeight = Integer.MIN_VALUE;
        for (String titleId : titleIds) {
            int weight = Title.weight(titleId);
            if (weight > bestWeight) {
                bestWeight = weight;
                bestId = titleId;
            }
        }
        return titleService.title(bestId).map(Title::displayName).orElse(null);
    }

    private void applyRefresh(
            List<PlayerRecord> ranked, List<Ban> recentBans, List<MuteDao.MuteRecord> mutes,
            Map<UUID, String> titleLabels) {
        long now = System.currentTimeMillis();
        List<WebPanelSnapshot.PlayerEntry> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            String label = titleLabels.getOrDefault(uuid, approximateRankId(uuid));
            online.add(new WebPanelSnapshot.PlayerEntry(player.getName(), uuid.toString(), label));
        }

        List<WebPanelSnapshot.StaffEntry> staff = new ArrayList<>();
        for (PlayerRecord record : ranked) {
            String label = titleLabels.getOrDefault(record.uuid(), record.rankId());
            staff.add(new WebPanelSnapshot.StaffEntry(
                    record.lastKnownName(), record.uuid().toString(), label, Bukkit.getPlayer(record.uuid()) != null));
        }

        List<WebPanelSnapshot.BanEntry> bans = new ArrayList<>();
        for (Ban ban : recentBans) {
            // User-requested: don't show expired bans here. Also the real fix for a latent
            // bug this surfaced - ban.active() is only ever flipped false by a manual
            // revoke/unban (BanDao#revoke*), never by a temp ban's own expires_at simply
            // passing, so a naturally-expired temp ban that was never manually unbanned
            // used to keep showing up marked "Active" forever. isCurrentlyInEffect checks
            // both (not revoked AND not time-expired) - the actually-correct definition,
            // matching how the mute list right below already filters expired entries.
            if (!ban.isCurrentlyInEffect(now)) {
                continue;
            }
            String target = ban.targetLastName() != null
                    ? ban.targetLastName()
                    : (ban.targetIpHash() != null ? "(ip)" : "(unknown)");
            bans.add(new WebPanelSnapshot.BanEntry(
                    ban.type().name(), target, ban.reason(), ban.createdAt(), ban.expiresAt(), ban.active()));
        }

        List<WebPanelSnapshot.MuteEntry> muteEntries = new ArrayList<>();
        for (MuteDao.MuteRecord mute : mutes) {
            if (mute.isExpired(now)) {
                continue;
            }
            Player mutedPlayer = Bukkit.getPlayer(mute.uuid());
            String name = mutedPlayer != null ? mutedPlayer.getName() : mute.uuid().toString();
            muteEntries.add(new WebPanelSnapshot.MuteEntry(name, mute.reason(), mute.createdAt(), mute.expiresAt()));
        }

        double[] recentTps = Bukkit.getServer().getTPS();
        double tps = recentTps.length > 0 ? recentTps[0] : 20.0;

        this.snapshot = new WebPanelSnapshot(
                now, now - startedAt, tps, online.size(), Bukkit.getServer().getMaxPlayers(), online, staff, bans,
                muteEntries);
    }

    /** Cache-only rank lookup, same approach as {@code punish.PunishModule#onlineApproxRank}. */
    private String approximateRankId(UUID uuid) {
        for (Rank rank : permissionGate.laddersDescending()) {
            if (permissionGate.hasAtLeastCached(uuid, rank.id())) {
                return rank.id();
            }
        }
        return "default";
    }
}
