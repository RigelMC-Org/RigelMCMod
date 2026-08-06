package org.rigelmc.core;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.chat.PlayerDisplayService;
import org.rigelmc.chat.TabListBroadcaster;
import org.rigelmc.data.dao.IpHistoryDao;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.identity.IdentityService;
import org.rigelmc.identity.IpHasher;
import org.rigelmc.identity.PlayerIdentity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.rigelmc.myadmin.LoginMessageDao;
import org.rigelmc.nick.NickService;
import org.rigelmc.rank.NameTagService;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.PrefixService;
import org.rigelmc.rank.Rank;
import org.rigelmc.rank.RankService;
import org.rigelmc.rank.TitleService;
import org.rigelmc.rank.VaultChatBridge;
import org.rigelmc.tag.TagService;

/**
 * Runs on every join: upserts {@code rigel_players}, records this login in
 * {@code rigel_ip_history} for moderation visibility and future {@code /permban}
 * cascades (see docs/architecture.md), resolves the player's {@link PlayerIdentity},
 * applies their live {@link org.bukkit.permissions.PermissionAttachment} via
 * {@link PermissionGate}, grants any config-configured titles (see
 * {@code RigelConfig#configuredTitleHolders}), loads their nickname/tag/rank-prefix into
 * cache, and pushes the composed tab-list name + header/footer + nametag color. The DB
 * work runs on {@link org.rigelmc.data.RigelExecutors}'s executor, hopping back to the
 * main thread only for the final Bukkit-API calls.
 *
 * <p>Also owns the join/quit broadcast (replacing vanilla's own message entirely, {@code
 * messages.yml}-backed) - TFM-style ({@code JoinLeaveMessages.java}, studied directly from
 * its real source): staff (Moderator+) get a distinctly visible announcement, matching
 * TFM's "admin presence is always broadcast" convention. This intentionally lives here
 * rather than in its own listener: the join announcement needs to know the player's
 * <i>resolved</i> rank, and {@link PermissionGate}'s online-rank cache is only populated
 * once {@link PermissionGate#applyRank} runs below (after this listener's own async DB
 * round-trip) - a separate listener reacting to the raw, synchronous {@code
 * PlayerJoinEvent} would race that and misdetect staff as non-staff on their own join (see
 * {@code vanish.VanishListener#onJoin} for an existing, unrelated example of exactly this
 * race - not fixed here, out of scope for this change).</p>
 */
public final class PlayerLoginListener implements Listener {

    private final RigelMCMod plugin;
    private final PlayerDao playerDao;
    private final IpHistoryDao ipHistoryDao;
    private final IpHasher ipHasher;
    private final IdentityService identityService;
    private final RankService rankService;
    private final TitleService titleService;
    private final PermissionGate permissionGate;
    private final PrefixService prefixService;
    private final NickService nickService;
    private final TagService tagService;
    private final PlayerDisplayService displayService;
    private final TabListBroadcaster tabListBroadcaster;
    private final NameTagService nameTagService;
    private final VaultChatBridge vaultChatBridge;
    private final LoginMessageDao loginMessageDao;
    private final ExecutorService dbExecutor;

    public PlayerLoginListener(
            @NotNull RigelMCMod plugin,
            @NotNull PlayerDao playerDao,
            @NotNull IpHistoryDao ipHistoryDao,
            @NotNull IpHasher ipHasher,
            @NotNull IdentityService identityService,
            @NotNull RankService rankService,
            @NotNull TitleService titleService,
            @NotNull PermissionGate permissionGate,
            @NotNull PrefixService prefixService,
            @NotNull NickService nickService,
            @NotNull TagService tagService,
            @NotNull PlayerDisplayService displayService,
            @NotNull TabListBroadcaster tabListBroadcaster,
            @NotNull NameTagService nameTagService,
            @NotNull VaultChatBridge vaultChatBridge,
            @NotNull LoginMessageDao loginMessageDao,
            @NotNull ExecutorService dbExecutor) {
        this.plugin = plugin;
        this.playerDao = playerDao;
        this.ipHistoryDao = ipHistoryDao;
        this.ipHasher = ipHasher;
        this.identityService = identityService;
        this.rankService = rankService;
        this.titleService = titleService;
        this.permissionGate = permissionGate;
        this.prefixService = prefixService;
        this.nickService = nickService;
        this.tagService = tagService;
        this.displayService = displayService;
        this.tabListBroadcaster = tabListBroadcaster;
        this.nameTagService = nameTagService;
        this.vaultChatBridge = vaultChatBridge;
        this.loginMessageDao = loginMessageDao;
        this.dbExecutor = dbExecutor;
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        event.joinMessage(null); // replaced below, once rank is resolved - see class javadoc
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;
        PlayerIdentity identity = identityService.resolve(uuid, plugin.rigelConfig());

        dbExecutor.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                playerDao.upsertOnLogin(uuid, name, identity, now);
                if (ip != null) {
                    ipHistoryDao.recordSeen(uuid, ipHasher.hash(ip), now);
                }
                grantConfiguredTitles(uuid, name, now);

                Rank rank = rankService.rankOf(uuid);
                var titleIds = titleService.titleIdsFor(uuid);
                prefixService.refresh(uuid, plugin.rigelConfig());
                nickService.loadIntoCache(uuid);
                Optional<String> loginMessage = loginMessageDao.find(uuid);
                // No tagService load here, deliberately - tags are session-only and
                // never persisted, so a fresh join always starts with no tag.

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player stillOnline = Bukkit.getPlayer(uuid);
                    if (stillOnline != null) {
                        permissionGate.applyRank(stillOnline, rank.id());
                        // Vanilla/Paper syncs a client's Brigadier command tree once, right at
                        // login - before this async rank lookup has had a chance to finish. A
                        // rank-gated command's top-level .requires() therefore evaluates false
                        // at that first sync for literally every joining player (the cache
                        // this predicate reads is only populated by the applyRank call just
                        // above), so it silently never appears in tab-completion for the rest
                        // of the session even though it still runs fine when typed manually
                        // (.requires() is re-checked server-side at actual dispatch time, by
                        // which point the cache is populated). This re-syncs the tree now that
                        // the real rank is known, fixing tab-completion for every rank-gated
                        // command at once - see rank.RankAdminModule's matching calls for the
                        // same fix after a live rank change.
                        stillOnline.updateCommands();
                        // Corrects the generic [OP] bracket's visibility for the just-
                        // refreshed prefix cache using this live Player - see
                        // PrefixService's javadoc for why refresh() alone (above, off the
                        // main thread, no live Player) can't resolve this by itself.
                        prefixService.applyOpStatus(uuid, stillOnline.isOp());
                        displayService.apply(stillOnline);
                        tabListBroadcaster.apply(stillOnline, plugin.rigelConfig());
                        // Unconditional - nametag coloring isn't gated behind
                        // modules.scoreboard.enabled, see NameTagService's javadoc.
                        nameTagService.applyScoreboard(stillOnline);
                        nameTagService.applyTeam(stillOnline, rank, titleIds);
                        // No-op if Vault/a Chat provider isn't present - see
                        // VaultChatBridge's javadoc for why this exists.
                        vaultChatBridge.setPrefix(stillOnline,
                                LegacyComponentSerializer.legacySection().serialize(prefixService.prefixFor(uuid)));
                        announceJoin(stillOnline, loginMessage);
                    }
                });
            } catch (SQLException | RuntimeException e) {
                // Deliberately broader than just SQLException: this whole block runs
                // inside dbExecutor.submit(Runnable), and ExecutorService silently
                // discards any exception an unretrieved Future's task throws - a bug
                // anywhere in this chain (not just a DB failure) would otherwise vanish
                // with zero log trace, leaving this player's PermissionGate cache entry
                // (and everything gated on it - every rank-gated command) permanently
                // unpopulated for their whole session with no diagnostic trail at all.
                // rank.RankAdminModule's periodic cache self-heal is the safety net that
                // eventually corrects the missing cache entry regardless; this log line
                // is what actually makes the root cause diagnosable if it ever fires.
                plugin.getLogger().log(Level.WARNING, "Failed to process login data for " + name, e);
            }
        });
    }

    /**
     * Grants any title whose configured ({@code config.yml}'s {@code titles} section)
     * username list contains this player's name (case-insensitive), and also ensures
     * they actually hold at least Senior Admin rank once granted.
     *
     * <p>The rank-pairing is a real bug fix, not a guess: {@link Title}'s own javadoc
     * documents Developer/Executive/Owner as titles "worn by Senior Admins," not
     * standalone honorifics, but this method previously only ever granted the title
     * itself. A player config-listed as e.g. {@code titles.owner} then showed the
     * correct {@code [Owner]} chat prefix (since {@link PrefixService} picks whichever
     * single highest-weight title/rank a player holds, independent of the other) while
     * every rank-gated command stayed completely invisible to them - {@code /ban} gave
     * Brigadier's raw "Unknown or incomplete command" with no indication why, because a
     * {@link Title} intentionally grants no permissions on its own (see its own
     * javadoc); only a real {@link Rank} does, and this player had never actually been
     * {@code /setrank}'d to one. Never downgrades an existing higher rank. Idempotent
     * via {@link TitleService#ensureGranted}/the weight check below - safe to run on
     * every single join.
     */
    private void grantConfiguredTitles(UUID uuid, String name, long now) throws SQLException {
        for (var entry : plugin.rigelConfig().configuredTitleHolders().entrySet()) {
            String titleId = entry.getKey();
            boolean configuredForThisPlayer =
                    entry.getValue().stream().anyMatch(configuredName -> configuredName.equalsIgnoreCase(name));
            if (configuredForThisPlayer) {
                try {
                    titleService.ensureGranted(uuid, titleId, null, now);
                    if (rankService.rankOf(uuid).weight() < Rank.SENIOR_ADMIN.weight()) {
                        rankService.setRank(uuid, Rank.SENIOR_ADMIN.id());
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger()
                            .warning("config.yml titles." + titleId + " references an unknown title id - ignoring.");
                }
            }
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        event.quitMessage(null); // replaced below - see class javadoc
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // Read before permissionGate.clear() below removes this player from the cache
        // that backs it.
        boolean staff = permissionGate.hasAtLeastCached(uuid, "moderator");

        permissionGate.clear(player);
        prefixService.clear(uuid);
        nickService.evict(uuid);
        tagService.evict(uuid);
        nameTagService.clear(player);

        broadcastJoinLeave(staff
                ? plugin.messages().get("joinleave.staff-quit",
                        "<gold>[Staff] <yellow>{player}</yellow> has left the server.", "player", player.getName())
                : plugin.messages().get("joinleave.quit",
                        "<gray><italic>{player} has left the game.</italic></gray>", "player", player.getName()));
    }

    /**
     * Called from the deferred main-thread callback in {@link #onJoin} - after {@link
     * PermissionGate#applyRank} has already run, so {@link PermissionGate#hasAtLeastCached}
     * is guaranteed correct here (see this class's javadoc for why that ordering matters).
     *
     * @param loginMessage the joining player's own personal join flavor line, if they set
     *     one via {@code /myadmin setlogin} - TFM ref: {@code Command_myadmin}'s {@code
     *     setlogin}. Only ever shown for staff (matches the staff-join line itself being
     *     staff-only) - a non-staff player who somehow had a stale row from a since-demoted
     *     rank doesn't get an extra broadcast line.
     */
    private void announceJoin(Player player, Optional<String> loginMessage) {
        boolean staff = permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator");
        broadcastJoinLeave(staff
                ? plugin.messages().get("joinleave.staff-join",
                        "<gold><bold>[Staff]</bold> <yellow>{player}</yellow> has joined the server!",
                        "player", player.getName())
                : plugin.messages().get("joinleave.join",
                        "<gray><italic>{player} has joined the game.</italic></gray>", "player", player.getName()));

        if (staff && loginMessage.isPresent()) {
            Component formatted = org.rigelmc.chat.ColorCodes.parse(loginMessage.get());
            Component prefix = plugin.messages().get("joinleave.staff-login-message-prefix", "<gray>  > ");
            broadcastJoinLeave(prefix.append(formatted));
        }
    }

    private void broadcastJoinLeave(Component message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(message);
        }
    }
}
