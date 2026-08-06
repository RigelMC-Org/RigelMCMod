package org.rigelmc.chat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.discord.DiscordBotManager;
import org.rigelmc.punish.mute.MuteService;
import org.rigelmc.rank.NameTagService;
import org.rigelmc.rank.PermissionGate;

/**
 * Chat-related features: mute enforcement and the {@code /o} staff (admin) chat command
 * - see docs/architecture.md "Discord bridge & admin chat". {@code /o} also mirrors into
 * the Discord admin channel via {@link DiscordBotManager} when that bridge is connected;
 * {@link DiscordBotManager#relayAdminMessage} is a safe no-op when it isn't, so {@code /o}
 * still works in-game-only if Discord isn't configured at all.
 */
public final class ChatModule implements PluginModule {

    private final MuteService muteService;
    private final PermissionGate permissionGate;
    private final DiscordBotManager discordBotManager;
    private final PlayerDisplayService displayService;
    private final TabListBroadcaster tabListBroadcaster;
    private final NameTagService nameTagService;

    public ChatModule(
            @NotNull MuteService muteService,
            @NotNull PermissionGate permissionGate,
            @NotNull DiscordBotManager discordBotManager,
            @NotNull PlayerDisplayService displayService,
            @NotNull TabListBroadcaster tabListBroadcaster,
            @NotNull NameTagService nameTagService) {
        this.muteService = muteService;
        this.permissionGate = permissionGate;
        this.discordBotManager = discordBotManager;
        this.displayService = displayService;
        this.tabListBroadcaster = tabListBroadcaster;
        this.nameTagService = nameTagService;
    }

    @Override
    public String id() {
        return "chat";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new MuteEnforcementListener(muteService, plugin.getLogger()), plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new ChatFormatListener(plugin, displayService, discordBotManager, plugin.getLogger()), plugin);

        // Keep the tab-list footer's online count current for everyone, AND
        // defensively re-push each player's tab-list name/display-name/scoreboard
        // assignment - not just at join/rank-change time. This is a deliberate
        // TFM-inspired pattern (its own TabList service does the same full-refresh loop,
        // not just header/footer) that makes RigelMCMod's own tab-list rendering
        // self-healing: if some other plugin ever overwrites playerListName between our
        // own trigger points, this puts it back within 5s instead of leaving it wrong
        // until the next join or rank change. All three calls here are cache-only reads
        // (PrefixService's cache, no DB) - cheap enough to run on every tick of this timer
        // for every online player.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            RigelConfig config = plugin.rigelConfig();
            tabListBroadcaster.applyToAll(config);
            for (Player online : Bukkit.getOnlinePlayers()) {
                displayService.apply(online);
                nameTagService.applyScoreboard(online);
            }
        }, 20L, 100L);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        // Primary literal is "o" (TFM's own most-used short alias), matching this
        // codebase's existing "flip the primary/alias to the shorter name" convention
        // (see /tban's own "tempban" alias below for the same pattern) - "adminchat" is
        // TFM's canonical full name, added here as the missing alias.
        registrar.register(adminChatCommand(), "Broadcast a message to online staff",
                java.util.List.of("ac", "adminchat"));
    }

    private LiteralCommandNode<CommandSourceStack> adminChatCommand() {
        return Commands.literal("o")
                .requires(source -> source.getSender() instanceof Player player
                        && permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/o <message>"))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            String message = StringArgumentType.getString(ctx, "message");
                            broadcastToStaff(sender, message);
                            return 1;
                        }))
                .build();
    }

    private void broadcastToStaff(CommandSender sender, String message) {
        Component formatted = Component.text("[Staff] " + sender.getName() + ": " + message, NamedTextColor.LIGHT_PURPLE);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (permissionGate.hasAtLeastCached(online.getUniqueId(), "moderator")) {
                online.sendMessage(formatted);
            }
        }
        Bukkit.getConsoleSender().sendMessage(formatted);
        discordBotManager.relayAdminMessage(sender.getName(), message);
    }
}
