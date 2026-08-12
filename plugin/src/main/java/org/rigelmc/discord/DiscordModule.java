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
import org.rigelmc.economy.InviteCreditService;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.RankService;
import org.rigelmc.vanish.VanishService;

/**
 * The optional Discord bridge - see docs/architecture.md "Discord bridge & admin chat".
 * Off by default; requires {@code discord.bot-token} to actually connect. Registers
 * {@code /discord link}/{@code /discord unlink} regardless of whether the bot is
 * currently connected, so the linking flow's failure mode is a clear in-game message,
 * not a missing command.
 *
 * <p>Also schedules the Discord-invite-credit sweep ({@link InviteCreditService#sweep}) on
 * a fixed {@code economy.invites.sweep-interval-seconds} timer, unconditionally - a no-op
 * pass (an empty {@code findDueForSweep} result) if invite tracking isn't actually
 * configured, which is simpler and no more wasteful than gating the timer itself behind
 * the same config this module already reads fresh on every run.</p>
 */
public final class DiscordModule implements PluginModule {

    private final DiscordLinkService linkService;
    private final DiscordBotManager botManager;
    private final RankService rankService;
    private final PermissionGate permissionGate;
    private final AuditLogService auditLogService;
    private final ExecutorService dbExecutor;
    private final VanishService vanishService;
    private final InviteCreditService inviteCreditService;
    private RigelMCMod plugin;

    public DiscordModule(
            @NotNull DiscordLinkService linkService,
            @NotNull DiscordBotManager botManager,
            @NotNull RankService rankService,
            @NotNull PermissionGate permissionGate,
            @NotNull AuditLogService auditLogService,
            @NotNull ExecutorService dbExecutor,
            @NotNull VanishService vanishService,
            @NotNull InviteCreditService inviteCreditService) {
        this.linkService = linkService;
        this.botManager = botManager;
        this.rankService = rankService;
        this.permissionGate = permissionGate;
        this.auditLogService = auditLogService;
        this.dbExecutor = dbExecutor;
        this.vanishService = vanishService;
        this.inviteCreditService = inviteCreditService;
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
        botManager.start(
                plugin.rigelConfig(), plugin, linkService, rankService, permissionGate, auditLogService,
                inviteCreditService);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new PublicChatBridgeListener(botManager), plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new JoinLeaveBridgeListener(botManager, plugin, vanishService, rankService, dbExecutor), plugin);

        long periodTicks = ticksFrom(plugin.rigelConfig().economyInviteSweepInterval());
        Bukkit.getScheduler().runTaskTimer(plugin, () -> dbExecutor.submit(this::sweepInviteCredits), periodTicks, periodTicks);
    }

    private void sweepInviteCredits() {
        try {
            InviteCreditService.SweepResult result = inviteCreditService.sweep(System.currentTimeMillis());
            if (result.credited() > 0) {
                plugin.getLogger().info("Discord invite-credit sweep: credited " + result.credited() + " inviter(s).");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to run the Discord invite-credit sweep", e);
        }
    }

    private static long ticksFrom(Duration duration) {
        return Math.max(duration.toMillis() / 50, 1); // 50ms per tick, minimum 1 tick
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
