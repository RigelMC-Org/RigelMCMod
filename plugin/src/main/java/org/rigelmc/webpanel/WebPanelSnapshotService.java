package org.rigelmc.webpanel;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;
import org.rigelmc.punish.ban.Ban;
import org.rigelmc.punish.ban.BanDao;
import org.rigelmc.punish.mute.MuteDao;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.Rank;

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
    private final ExecutorService dbExecutor;
    private final long startedAt = System.currentTimeMillis();
    private volatile WebPanelSnapshot snapshot = WebPanelSnapshot.empty();

    public WebPanelSnapshotService(
            @NotNull RigelMCMod plugin,
            @NotNull PlayerDao playerDao,
            @NotNull BanDao banDao,
            @NotNull MuteDao muteDao,
            @NotNull PermissionGate permissionGate,
            @NotNull ExecutorService dbExecutor) {
        this.plugin = plugin;
        this.playerDao = playerDao;
        this.banDao = banDao;
        this.muteDao = muteDao;
        this.permissionGate = permissionGate;
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

                Bukkit.getScheduler().runTask(plugin, () -> applyRefresh(ranked, recentBans, mutes));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to refresh web panel snapshot", e);
            }
        });
    }

    private void applyRefresh(List<PlayerRecord> ranked, List<Ban> recentBans, List<MuteDao.MuteRecord> mutes) {
        List<WebPanelSnapshot.PlayerEntry> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(new WebPanelSnapshot.PlayerEntry(
                    player.getName(), player.getUniqueId().toString(), approximateRankId(player.getUniqueId())));
        }

        List<WebPanelSnapshot.StaffEntry> staff = new ArrayList<>();
        for (PlayerRecord record : ranked) {
            staff.add(new WebPanelSnapshot.StaffEntry(
                    record.lastKnownName(), record.uuid().toString(), record.rankId(),
                    Bukkit.getPlayer(record.uuid()) != null));
        }

        List<WebPanelSnapshot.BanEntry> bans = new ArrayList<>();
        for (Ban ban : recentBans) {
            String target = ban.targetLastName() != null
                    ? ban.targetLastName()
                    : (ban.targetIpHash() != null ? "(ip)" : "(unknown)");
            bans.add(new WebPanelSnapshot.BanEntry(
                    ban.type().name(), target, ban.reason(), ban.createdAt(), ban.expiresAt(), ban.active()));
        }

        List<WebPanelSnapshot.MuteEntry> muteEntries = new ArrayList<>();
        long now = System.currentTimeMillis();
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
