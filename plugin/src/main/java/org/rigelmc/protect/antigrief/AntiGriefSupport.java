package org.rigelmc.protect.antigrief;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.rank.PermissionGate;

/**
 * Small helpers shared across every anti-grief guard in this package: notifying online
 * staff of a breach, and applying a configured breach action consistently rather than each
 * guard duplicating the same "kick or just alert" branch.
 */
final class AntiGriefSupport {

    private AntiGriefSupport() {}

    static void notifyStaff(@NotNull PermissionGate permissionGate, @NotNull Component message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (permissionGate.hasAtLeastCached(online.getUniqueId(), "moderator")) {
                online.sendMessage(message);
            }
        }
    }

    /**
     * Applies a configured breach action: {@code "kick"} removes the player with the given
     * reason; anything else (including the not-yet-wired-up {@code "freeze"} - see
     * config.yml's comment on {@code anti-nuke.action} - falls back to alert-only) just
     * notifies staff without taking action against the offending player. Staff are always
     * notified regardless of the action taken.
     */
    static void applyAction(
            @NotNull String action,
            @NotNull Player player,
            @NotNull PermissionGate permissionGate,
            @NotNull String staffMessage,
            @NotNull String kickMessage) {
        notifyStaff(permissionGate, Component.text(staffMessage, NamedTextColor.RED));
        if ("kick".equalsIgnoreCase(action)) {
            player.kick(Component.text(kickMessage, NamedTextColor.RED));
        }
    }
}
