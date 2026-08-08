package org.rigelmc.protect.antigrief;

import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * Gamerule enforcement (silently re-applies configured defaults on an interval; flags
 * changes to any gamerule on the {@code protect.anti-grief.gamerules.malicious} list to
 * staff even though command-access already blocks the bare {@code /gamerule} command for
 * most players - defense in depth against another plugin, a console, or RCON) plus
 * gamemode-switch flood protection. TFM ref: GameRuleHandler.java, GameModeGuard.java.
 * Reads {@code plugin.rigelConfig()} fresh on every event/tick - see {@link
 * AntiNukeGuard}'s javadoc for why.
 */
public final class GameplayGuard implements Listener {

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;
    private final PerPlayerRateLimiter<UUID> gamemodeSwitches = new PerPlayerRateLimiter<>(10_000);

    public GameplayGuard(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    /** Schedules the periodic gamerule-default re-enforcement task. Call once on enable. */
    public void scheduleEnforcement() {
        long ticks = Math.max(1, plugin.rigelConfig().gameruleEnforceInterval().toMinutes()) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::enforceDefaults, ticks, ticks);
    }

    private void enforceDefaults() {
        if (!plugin.rigelConfig().gameruleGuardEnabled()) {
            return;
        }
        Map<String, String> defaults = plugin.rigelConfig().gameruleDefaults();
        if (defaults.isEmpty()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Map.Entry<String, String> entry : defaults.entrySet()) {
                applyGameRuleDefault(world, entry.getKey(), entry.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyGameRuleDefault(World world, String ruleName, String rawValue) {
        // Registry#match (not the deprecated GameRule#getByName) is the modern lookup -
        // confirmed via javap against the actual Paper 26.1.2 jar.
        GameRule<Object> rule = (GameRule<Object>) Registry.GAME_RULE.match(ruleName);
        if (rule == null) {
            plugin.getLogger().warning("Unknown gamerule '" + ruleName + "' in protect.anti-grief.gamerules.defaults");
            return;
        }
        Class<?> type = rule.getType();
        Object parsed;
        if (type == Boolean.class) {
            parsed = Boolean.parseBoolean(rawValue);
        } else if (type == Integer.class) {
            try {
                parsed = Integer.parseInt(rawValue);
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Invalid gamerule value '" + rawValue + "' for " + ruleName);
                return;
            }
        } else {
            return;
        }
        world.setGameRule(rule, parsed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameRuleChange(@NotNull WorldGameRuleChangeEvent event) {
        if (!plugin.rigelConfig().gameruleGuardEnabled()
                || !plugin.rigelConfig().maliciousGamerules().contains(event.getGameRule().getName())) {
            return;
        }
        AntiGriefSupport.notifyStaff(permissionGate, Component.text(
                "[GameRule] " + event.getCommandSender().getName() + " set " + event.getGameRule().getName()
                        + " to " + event.getValue() + " in " + event.getWorld().getName() + ".",
                NamedTextColor.YELLOW));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGameModeChange(@NotNull PlayerGameModeChangeEvent event) {
        if (!plugin.rigelConfig().gamemodeFloodGuardEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        int count = gamemodeSwitches.recordAndCount(player.getUniqueId(), System.currentTimeMillis());
        if (count > plugin.rigelConfig().gamemodeMaxSwitchesPer10s()) {
            event.setCancelled(true);
            player.sendMessage(Component.text("You're switching gamemodes too quickly.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        gamemodeSwitches.clear(event.getPlayer().getUniqueId());
    }
}
