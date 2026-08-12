package org.rigelmc.discord;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.RankService;
import org.rigelmc.vanish.VanishService;

/**
 * Relays player join/leave into Discord - every join/leave posts to the public channel
 * (the "server chat bridge"); only a staff (Moderator+) join/leave additionally posts to
 * the admin channel (user-reported: it was getting every player's join/leave, not just
 * staff, defeating the point of a dedicated staff-activity channel). Config-gated by
 * {@code discord.relay-join-leave} (default on).
 *
 * <p><b>Deliberately resolves "is this player staff" via {@link RankService#hasAtLeast},
 * off the main thread, rather than {@code rank.PermissionGate#hasAtLeastCached}</b> - the
 * obvious-looking synchronous cache read is wrong in both directions here:
 * <ul>
 *   <li>On join, {@code PermissionGate}'s online-rank cache is only populated once {@code
 *   core.PlayerLoginListener}'s own async DB round-trip finishes and hops back to the main
 *   thread - well after this listener's {@code MONITOR}-priority handler has already run
 *   in the same synchronous event dispatch. Reading the cache here would misdetect every
 *   staff member as non-staff on their own join (the same class of race {@code
 *   vanish.VanishListener#onJoin}'s own javadoc already documents).</li>
 *   <li>On quit, it's actually worse: {@code PlayerLoginListener#onQuit} runs at default
 *   ({@code NORMAL}) priority and calls {@code PermissionGate#clear} as its very first
 *   action - before this listener's {@code MONITOR} handler (which always runs after
 *   {@code NORMAL} in the same dispatch) ever gets a chance to read it. A synchronous
 *   cache read here would read a cache already emptied for every single quitting player,
 *   staff included, every time.</li>
 * </ul>
 * {@link RankService#hasAtLeast} instead queries the same DB-backed rank record {@code
 * PlayerLoginListener} itself resolves from, independent of {@code PermissionGate}'s
 * join-lifecycle cache population/clearing entirely - correct in both directions, at the
 * cost of one small DB round-trip per join/leave, dispatched via {@code dbExecutor} like
 * every other DB access in this codebase (never on the main thread).</p>
 *
 * <p>Vanish-aware on leave only: {@link VanishService} is deliberately session-only and
 * always resets to "not vanished" on a fresh login (see its own javadoc), so a
 * just-joined player is never actually vanished yet - nothing to check on join. On leave,
 * though, a currently-vanished player's departure is only posted to the public channel if
 * they weren't vanished; announcing it publicly would leak that they'd been online at all,
 * defeating the entire point of vanishing in the first place. The admin channel still gets
 * a vanished staff member's departure regardless, on the same staff-only basis as anyone
 * else's.</p>
 */
public final class JoinLeaveBridgeListener implements Listener {

    private final DiscordBotManager botManager;
    private final RigelMCMod plugin;
    private final VanishService vanishService;
    private final RankService rankService;
    private final ExecutorService dbExecutor;

    public JoinLeaveBridgeListener(
            @NotNull DiscordBotManager botManager, @NotNull RigelMCMod plugin, @NotNull VanishService vanishService,
            @NotNull RankService rankService, @NotNull ExecutorService dbExecutor) {
        this.botManager = botManager;
        this.plugin = plugin;
        this.vanishService = vanishService;
        this.rankService = rankService;
        this.dbExecutor = dbExecutor;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        if (!botManager.isReady() || !plugin.rigelConfig().discordRelayJoinLeave()) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        dbExecutor.submit(() -> relay(uuid, "**" + name + "** joined the game.", true));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        if (!botManager.isReady() || !plugin.rigelConfig().discordRelayJoinLeave()) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        boolean visible = !vanishService.isVanished(uuid);
        dbExecutor.submit(() -> relay(uuid, "**" + name + "** left the game.", visible));
    }

    /** Off the main thread - see class javadoc for why this resolves staff status via {@link RankService}, not {@code PermissionGate}. */
    private void relay(@NotNull UUID uuid, @NotNull String message, boolean includePublic) {
        try {
            boolean staff = rankService.hasAtLeast(uuid, "moderator");
            botManager.relayJoinLeave(message, includePublic, staff);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to resolve rank for a Discord join/leave relay", e);
        }
    }
}
