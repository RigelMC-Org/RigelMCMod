package org.rigelmc.discord;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.RankService;

/**
 * The optional Discord bridge - see docs/architecture.md "Discord bridge & admin chat".
 * Off by default; requires {@code discord.bot-token} to actually connect. Registers
 * {@code /discord link}/{@code /discord unlink} regardless of whether the bot is
 * currently connected, so the linking flow's failure mode is a clear in-game message,
 * not a missing command.
 */
public final class DiscordModule implements PluginModule {

    private final DiscordLinkService linkService;
    private final DiscordBotManager botManager;
    private final RankService rankService;
    private final PermissionGate permissionGate;
    private final AuditLogService auditLogService;
    private final ExecutorService dbExecutor;
    private RigelMCMod plugin;

    public DiscordModule(
            @NotNull DiscordLinkService linkService,
            @NotNull DiscordBotManager botManager,
            @NotNull RankService rankService,
            @NotNull PermissionGate permissionGate,
            @NotNull AuditLogService auditLogService,
            @NotNull ExecutorService dbExecutor) {
        this.linkService = linkService;
        this.botManager = botManager;
        this.rankService = rankService;
        this.permissionGate = permissionGate;
        this.auditLogService = auditLogService;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String id() {
        return "discord";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        botManager.start(plugin.rigelConfig(), plugin, linkService, rankService, permissionGate, auditLogService);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new PublicChatBridgeListener(botManager), plugin);
        plugin.getLogger().addHandler(new ConsoleRelayHandler(botManager));
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(discordCommand(), "Link/unlink your Discord account");
    }

    private LiteralCommandNode<CommandSourceStack> discordCommand() {
        return Commands.literal("discord")
                .executes(ctx -> org.rigelmc.command.CommandUsage.show(
                        ctx.getSource().getSender(), "/discord link|unlink"))
                .then(Commands.literal("link").executes(this::executeLink))
                .then(Commands.literal("unlink").executes(this::executeUnlink))
                .build();
    }

    private int executeLink(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("Only players can link a Discord account.", NamedTextColor.RED));
            return 0;
        }
        dbExecutor.submit(() -> {
            try {
                Duration ttl = plugin.rigelConfig().discordLinkCodeTtl();
                String code = linkService.createLinkCode(player.getUniqueId(), ttl, System.currentTimeMillis());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(Component.text(
                            "Run \"/link code:" + code + "\" as a DM to the bot within " + ttl.toMinutes()
                                    + " minutes to link your account.",
                            NamedTextColor.GOLD));
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to create Discord link code", e);
                Bukkit.getScheduler()
                        .runTask(plugin, () -> player.sendMessage(
                                Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    private int executeUnlink(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("Only players can unlink a Discord account.", NamedTextColor.RED));
            return 0;
        }
        dbExecutor.submit(() -> {
            try {
                boolean unlinked = linkService.unlink(player.getUniqueId());
                Bukkit.getScheduler()
                        .runTask(plugin, () -> player.sendMessage(unlinked
                                ? Component.text("Your Discord account has been unlinked.", NamedTextColor.GREEN)
                                : Component.text("You don't have a linked Discord account.", NamedTextColor.YELLOW)));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to unlink Discord account", e);
                Bukkit.getScheduler()
                        .runTask(plugin, () -> player.sendMessage(
                                Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }
}
