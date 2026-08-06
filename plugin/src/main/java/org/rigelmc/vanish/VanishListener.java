package org.rigelmc.vanish;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * Keeps vanish visibility correct as players come and go: a newly-joined non-staff
 * player must not see anyone currently vanished, and a vanished player's own state is
 * cleared on quit (see {@link VanishService}'s javadoc for why it's session-only).
 */
public final class VanishListener implements Listener {

    private final RigelMCMod plugin;
    private final VanishService vanishService;
    private final PermissionGate permissionGate;

    public VanishListener(
            @NotNull RigelMCMod plugin, @NotNull VanishService vanishService, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.vanishService = vanishService;
        this.permissionGate = permissionGate;
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        boolean joinedIsStaff = permissionGate.hasAtLeastCached(joined.getUniqueId(), "moderator");
        if (joinedIsStaff) {
            return; // staff can see everyone, including vanished admins - nothing to hide
        }
        for (UUID vanishedUuid : vanishService.vanishedPlayers()) {
            Player vanishedPlayer = Bukkit.getPlayer(vanishedUuid);
            if (vanishedPlayer != null && !vanishedPlayer.equals(joined)) {
                joined.hidePlayer(plugin, vanishedPlayer);
            }
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        vanishService.clear(event.getPlayer().getUniqueId());
    }
}
