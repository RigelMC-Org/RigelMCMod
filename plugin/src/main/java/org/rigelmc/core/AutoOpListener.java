package org.rigelmc.core;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

/**
 * The defining trait of a Free-OP server: every joining player is granted vanilla
 * Bukkit operator status unconditionally. Runs at {@link EventPriority#LOWEST} so op
 * status is already in place before other plugins' own join listeners run and might
 * check it or need op-gated permissions immediately.
 *
 * <p><b>This is exactly why the tiered command-access system in {@code protect/} exists
 * and does not rely on Bukkit permissions for its own gating</b>: vanilla op status
 * bypasses every permission check that isn't explicitly registered with a non-OP
 * default (see the note in {@code ProtectModule#registerListeners}), including other
 * plugins' own dangerous op-gated commands (e.g. Essentials' {@code /tpo}). Blocking
 * those for the auto-op'd masses while still allowing RigelMCMod-ranked staff to use
 * them is what {@code protect.command-access} is for - see {@code config.yml}.</p>
 */
public final class AutoOpListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.isOp()) {
            player.setOp(true);
        }
    }
}
