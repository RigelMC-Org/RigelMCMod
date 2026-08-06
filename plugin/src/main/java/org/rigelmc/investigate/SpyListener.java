package org.rigelmc.investigate;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Relays other players' commands/sign edits/thrown potions to staff who've opted into the
 * matching spy mode - TFM ref: {@code CommandSpy.java}, {@code SignSpy.java}, {@code
 * PotionSpy.java}, studied directly, simplified to this codebase's own session-only
 * toggle model (see {@link SpyService}'s javadoc) rather than porting TFM's ADMINS/OPS/
 * ALL command-spy submodes or its fake-client-side-sign preview feature.
 *
 * <p>{@code MONITOR} priority throughout - relaying happens after the underlying action
 * has already been decided (dispatched/cancelled by something else), never interferes
 * with it.</p>
 */
public final class SpyListener implements Listener {

    private final SpyService spyService;

    public SpyListener(@NotNull SpyService spyService) {
        this.spyService = spyService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        Set<UUID> spies = spyService.commandSpies();
        if (spies.isEmpty()) {
            return;
        }
        Player sender = event.getPlayer();
        relay(spies, sender.getUniqueId(),
                Component.text("[CmdSpy] " + sender.getName() + ": " + event.getMessage(), NamedTextColor.GRAY));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(@NotNull SignChangeEvent event) {
        Set<UUID> spies = spyService.signSpies();
        if (spies.isEmpty()) {
            return;
        }
        Player editor = event.getPlayer();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < event.getLines().length; i++) {
            String line = event.getLine(i);
            if (line != null && !line.isEmpty()) {
                if (lines.length() > 0) {
                    lines.append(" | ");
                }
                lines.append(line);
            }
        }
        relay(spies, editor.getUniqueId(), Component.text(
                "[SignSpy] " + editor.getName() + " wrote a sign: " + lines, NamedTextColor.GRAY));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileLaunch(@NotNull ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion potion)) {
            return;
        }
        Set<UUID> spies = spyService.potionSpies();
        if (spies.isEmpty() || !(potion.getShooter() instanceof Player thrower)) {
            return;
        }
        String potionName = potion.getItem().getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        relay(spies, thrower.getUniqueId(), Component.text(
                "[PotionSpy] " + thrower.getName() + " threw a " + potionName + " at "
                        + formatLocation(potion.getLocation()),
                NamedTextColor.GRAY));
    }

    private static void relay(Set<UUID> spies, UUID subjectUuid, Component message) {
        for (UUID spyUuid : spies) {
            if (spyUuid.equals(subjectUuid)) {
                continue; // don't echo a player's own action back to themselves
            }
            Player spy = Bukkit.getPlayer(spyUuid);
            if (spy != null) {
                spy.sendMessage(message);
            }
        }
    }

    private static String formatLocation(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }
}
