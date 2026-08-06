package org.rigelmc.disguise;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.rank.PermissionGate;

/**
 * Command-preprocess-level enforcement of {@link DisallowedDisguises} - the actual fix for
 * the dead-code gap found in TFM's real source (see {@link DisallowedDisguises}'s own
 * javadoc): TFM defines a forbidden-disguise-type list and a global enabled/disabled flag
 * but never wires either into anything that blocks a real {@code /disguise} command.
 *
 * <p>Deliberately string/regex matching on the raw command text, same "verified, lower-risk
 * first" scoping already used by {@code protect.worldedit.WorldEditAbuseGuard} - no
 * compile-time LibsDisguises dependency, no assumption about an internal disguise-created
 * event this project has no jar to verify the existence or exact shape of. This isn't
 * perfect (a disguise type that never appears as a standalone command argument token would
 * slip through), but it is a real, working enforcement where TFM's own equivalent is
 * entirely inert - not a regression relative to it in any case.</p>
 *
 * <p>Resolves whether a typed command is LibsDisguises' own via the live {@code
 * CommandMap}'s resolved owning plugin, matching {@code WorldEditAbuseGuard}/{@code
 * protect.ProtectModule}'s existing alias-resolution pattern - robust regardless of which
 * of LibsDisguises' several command labels/aliases a player actually types.</p>
 */
public final class DisguiseAbuseGuard implements Listener {

    private final DisallowedDisguises disallowedDisguises;
    private final PermissionGate permissionGate;

    public DisguiseAbuseGuard(
            @NotNull DisallowedDisguises disallowedDisguises, @NotNull PermissionGate permissionGate) {
        this.disallowedDisguises = disallowedDisguises;
        this.permissionGate = permissionGate;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(@NotNull PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator")) {
            return;
        }

        String rawMessage = event.getMessage();
        String withoutSlash = rawMessage.startsWith("/") ? rawMessage.substring(1) : rawMessage;
        String[] parts = withoutSlash.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty() || !isDisguiseCommand(parts[0])) {
            return;
        }

        if (disallowedDisguises.isDisabled()) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Disguises are currently disabled on this server.", NamedTextColor.RED));
            return;
        }

        for (int i = 1; i < parts.length; i++) {
            if (!disallowedDisguises.isAllowed(parts[i])) {
                event.setCancelled(true);
                player.sendMessage(
                        Component.text("You cannot disguise as that - it's on the forbidden list.", NamedTextColor.RED));
                return;
            }
        }
    }

    private boolean isDisguiseCommand(String label) {
        Command command = Bukkit.getServer().getCommandMap().getCommand(label);
        if (!(command instanceof PluginCommand pluginCommand)) {
            return false;
        }
        return "LibsDisguises".equalsIgnoreCase(pluginCommand.getPlugin().getName());
    }
}
