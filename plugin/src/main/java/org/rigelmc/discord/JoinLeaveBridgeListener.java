package org.rigelmc.discord;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.vanish.VanishService;

/**
 * Relays player join/leave into Discord - user-requested: join/leave on the public
 * channel (the "server chat bridge"), and separately on the admin channel (the "staff
 * chat bridge", "for staff only" per the request - that just describes who's actually in
 * that channel already, not an extra filter; every player's join/leave posts to both).
 * Config-gated by {@code discord.relay-join-leave} (default on).
 *
 * <p>Vanish-aware on leave only: {@link VanishService} is deliberately session-only and
 * always resets to "not vanished" on a fresh login (see its own javadoc), so a
 * just-joined player is never actually vanished yet - nothing to check on join. On leave,
 * though, a currently-vanished player's departure is only posted to the admin channel;
 * announcing it publicly would leak that they'd been online at all, defeating the entire
 * point of vanishing in the first place.</p>
 */
public final class JoinLeaveBridgeListener implements Listener {

    private final DiscordBotManager botManager;
    private final RigelMCMod plugin;
    private final VanishService vanishService;

    public JoinLeaveBridgeListener(
            @NotNull DiscordBotManager botManager, @NotNull RigelMCMod plugin, @NotNull VanishService vanishService) {
        this.botManager = botManager;
        this.plugin = plugin;
        this.vanishService = vanishService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        if (!botManager.isReady() || !plugin.rigelConfig().discordRelayJoinLeave()) {
            return;
        }
        botManager.relayJoinLeave("**" + event.getPlayer().getName() + "** joined the game.", true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        if (!botManager.isReady() || !plugin.rigelConfig().discordRelayJoinLeave()) {
            return;
        }
        boolean visible = !vanishService.isVanished(event.getPlayer().getUniqueId());
        botManager.relayJoinLeave("**" + event.getPlayer().getName() + "** left the game.", visible);
    }
}
