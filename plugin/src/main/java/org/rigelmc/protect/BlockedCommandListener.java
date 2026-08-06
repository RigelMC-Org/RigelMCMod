package org.rigelmc.protect;

import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.rank.PermissionGate;

/**
 * Enforces {@link CommandAccessRegistry}'s tiered command access before a command ever
 * reaches dispatch. Runs at {@link EventPriority#LOW} so it's one of the first listeners
 * to see the event - a blocked/under-ranked command should never get far enough to
 * trigger other plugins' own command-preprocess side effects (logging, cooldowns, etc.)
 * before being stopped.
 *
 * <p>Only ever intercepts {@link PlayerCommandPreprocessEvent} - console and RCON input
 * (which fire different events entirely) are never touched by this class at all. That's
 * also the mechanism for making a {@code protect.command-access} entry effectively
 * console/RCON-only: an {@code "n"} (nobody) rank tier blocks every <em>player</em>, while
 * the command keeps working unchanged from console/RCON, since there's nothing here to
 * intercept it there.</p>
 *
 * <p>Also closes a real bypass that TFM's own {@code CommandBlocker} explicitly guards
 * against too (confirmed by reading its source directly): Bukkit/Paper lets a player
 * disambiguate a command-name collision by explicitly namespacing it to a specific
 * plugin (e.g. {@code /essentials:tp} instead of the bare {@code /tp}). TFM blocks any
 * namespaced invocation outright, unconditionally, for every sender - no rank exception
 * at all - and this class matches that: an explicitly-namespaced command is never
 * checked against {@link CommandAccessRegistry} at all, since it's rejected before that
 * lookup even happens. Only the {@link #BYPASS_PERMISSION} escape hatch (RigelMCMod's
 * own addition, not present in TFM) can get past it.</p>
 */
public final class BlockedCommandListener implements Listener {

    /**
     * Hard override, independent of rank - lets a trusted operator bypass even a
     * {@code "n"} (nobody)-tier rule, or the namespaced-command block, in an emergency.
     * Not granted by any rank tier by default; an operator must opt a player into it
     * explicitly.
     */
    public static final String BYPASS_PERMISSION = "rigelmcmod.protect.bypass.blockedcommands";

    private final CommandAccessRegistry registry;
    private final PermissionGate permissionGate;

    public BlockedCommandListener(
            @NotNull CommandAccessRegistry registry, @NotNull PermissionGate permissionGate) {
        this.registry = registry;
        this.permissionGate = permissionGate;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommandPreprocess(@NotNull PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }

        String rawMessage = event.getMessage();
        String withoutSlash = rawMessage.startsWith("/") ? rawMessage.substring(1) : rawMessage;
        String[] parts = withoutSlash.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }
        String rawLabel = parts[0].toLowerCase(Locale.ROOT);

        if (rawLabel.contains(":")) {
            // Namespaced (plugin:command) invocation - unconditionally blocked, see
            // class javadoc. Matches TFM's own CommandBlocker#isCommandBlocked exactly.
            deny(event, player, CommandAccessRule.defaultDeniedMessage());
            return;
        }

        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        Optional<CommandAccessRule> matched = registry.match(rawLabel, args);
        if (matched.isEmpty()) {
            return; // no restriction configured for this command/argument combination
        }

        CommandAccessRule rule = matched.get();
        if (rule.requiredRankId() != null && permissionGate.hasAtLeastCached(player.getUniqueId(), rule.requiredRankId())) {
            return; // player meets the required rank - allowed
        }
        // requiredRankId() == null means the "n" (nobody) tier - always denied here.

        applyAction(event, player, rule);
    }

    private void applyAction(PlayerCommandPreprocessEvent event, Player player, CommandAccessRule rule) {
        event.setCancelled(true);
        switch (rule.action()) {
            case UNKNOWN -> player.sendMessage(Component.text("Unknown command. Type \"/help\" for help."));
            case KICK -> {
                player.sendMessage(rule.message());
                player.kick(rule.message());
            }
            case BLOCK -> player.sendMessage(rule.message());
        }
    }

    private static void deny(PlayerCommandPreprocessEvent event, Player player, Component message) {
        event.setCancelled(true);
        player.sendMessage(message);
    }
}
