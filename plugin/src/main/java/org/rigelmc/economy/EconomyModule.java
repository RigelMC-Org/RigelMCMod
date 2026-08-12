package org.rigelmc.economy;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.command.PlayerSuggestions;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;
import org.rigelmc.rank.PermissionGate;

/**
 * RigelMCMod's own currency - {@code /balance}, {@code /pay}, {@code /economy
 * give|take|set}. No Vault Economy dependency (confirmed before this was written: {@code
 * rank.VaultChatBridge} only bridges Vault's Chat API, never Economy - there was nothing
 * to build on there). Off by default ({@code modules.economy.enabled: false}) - the
 * earning side needs Discord invite tracking configured (a later sub-phase) to do
 * anything, but every command here works standalone the moment it's turned on regardless.
 *
 * <p>Kept as one file rather than a split {@code EconomyCommand} class - unlike {@code
 * protect.area.ProtectAreaCommand}/{@code guild.GuildCommand} (665+ lines, many
 * subcommands), this command surface is small enough to match {@code
 * punish.PunishModule}'s own single-file precedent instead.</p>
 */
public final class EconomyModule implements PluginModule {

    private final EconomyService economyService;
    private final PlayerDao playerDao;
    private final PermissionGate permissionGate;
    private final AuditLogService auditLogService;
    private final ExecutorService dbExecutor;
    private RigelMCMod plugin;

    public EconomyModule(
            @NotNull EconomyService economyService,
            @NotNull PlayerDao playerDao,
            @NotNull PermissionGate permissionGate,
            @NotNull AuditLogService auditLogService,
            @NotNull ExecutorService dbExecutor) {
        this.economyService = economyService;
        this.playerDao = playerDao;
        this.permissionGate = permissionGate;
        this.auditLogService = auditLogService;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(balanceCommand(), "Check your (or another player's) currency balance");
        registrar.register(payCommand(), "Pay another player some of your currency");
        registrar.register(economyCommand(), "Admin currency management - Moderator+");
    }

    // ---- /balance -------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> balanceCommand() {
        return Commands.literal("balance")
                .executes(this::executeBalanceSelf)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .requires(source -> hasRank(source, "moderator"))
                        .executes(this::executeBalanceOther))
                .build();
    }

    private int executeBalanceSelf(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            return CommandUsage.show(sender, "/balance <player>");
        }
        dbExecutor.submit(() -> {
            try {
                long balance = economyService.balanceOf(player.getUniqueId());
                sync(() -> sender.sendMessage(Component.text(
                        "Your balance: " + formatAmount(balance), NamedTextColor.GOLD)));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/balance", e);
            }
        });
        return 1;
    }

    private int executeBalanceOther(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        dbExecutor.submit(() -> {
            try {
                Optional<UUID> uuidOpt = resolveUuid(targetName);
                if (uuidOpt.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                long balance = economyService.balanceOf(uuidOpt.get());
                sync(() -> sender.sendMessage(Component.text(
                        targetName + "'s balance: " + formatAmount(balance), NamedTextColor.GOLD)));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/balance", e);
            }
        });
        return 1;
    }

    // ---- /pay -----------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> payCommand() {
        return Commands.literal("pay")
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/pay <player> <amount>"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/pay <player> <amount>"))
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(this::executePay)))
                .build();
    }

    private int executePay(CommandContext<CommandSourceStack> ctx) {
        Player sender = (Player) ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        long amount = LongArgumentType.getLong(ctx, "amount");

        if (targetName.equalsIgnoreCase(sender.getName())) {
            sender.sendMessage(Component.text("You can't pay yourself.", NamedTextColor.RED));
            return 0;
        }

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuid = resolveUuid(targetName);
                if (targetUuid.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                boolean success = economyService.transfer(sender.getUniqueId(), targetUuid.get(), amount);
                sync(() -> {
                    if (!success) {
                        sender.sendMessage(Component.text("You don't have enough " + currencyNamePlural() + ".", NamedTextColor.RED));
                        return;
                    }
                    sender.sendMessage(Component.text(
                            "Paid " + targetName + " " + formatAmount(amount) + ".", NamedTextColor.GREEN));
                    Player onlineTarget = Bukkit.getPlayerExact(targetName);
                    if (onlineTarget != null) {
                        onlineTarget.sendMessage(Component.text(
                                sender.getName() + " paid you " + formatAmount(amount) + ".", NamedTextColor.GREEN));
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/pay", e);
            }
        });
        return 1;
    }

    // ---- /economy give|take|set|top ---------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> economyCommand() {
        return Commands.literal("economy")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(
                        ctx.getSource().getSender(), "/economy give|take|set <player> <amount> | top [page]"))
                .then(Commands.literal("give")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> executeAdminAdjust(ctx, LedgerReason.ADMIN_GIVE)))))
                .then(Commands.literal("take")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> executeAdminAdjust(ctx, LedgerReason.ADMIN_TAKE)))))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(this::executeAdminSet))))
                .then(Commands.literal("top").executes(this::executeTop))
                .build();
    }

    private int executeAdminAdjust(CommandContext<CommandSourceStack> ctx, LedgerReason reason) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        long amount = LongArgumentType.getLong(ctx, "amount");
        UUID actorUuid = actorUuid(sender);

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuidOpt = resolveUuid(targetName);
                if (targetUuidOpt.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                UUID targetUuid = targetUuidOpt.get();
                long newBalance;
                if (reason == LedgerReason.ADMIN_GIVE) {
                    newBalance = economyService.credit(targetUuid, amount, reason, null, actorUuid);
                } else {
                    try {
                        newBalance = economyService.debit(targetUuid, amount, reason, null, actorUuid);
                    } catch (InsufficientFundsException e) {
                        sync(() -> sender.sendMessage(Component.text(
                                targetName + " only has " + formatAmount(e.available()) + ".", NamedTextColor.RED)));
                        return;
                    }
                }
                auditLogService.record(actorUuid, "ECONOMY_" + reason.name(), targetUuid,
                        formatAmount(amount) + " -> new balance " + formatAmount(newBalance));
                long finalBalance = newBalance;
                sync(() -> sender.sendMessage(Component.text(
                        (reason == LedgerReason.ADMIN_GIVE ? "Gave " : "Took ") + formatAmount(amount)
                                + (reason == LedgerReason.ADMIN_GIVE ? " to " : " from ") + targetName
                                + ". New balance: " + formatAmount(finalBalance),
                        NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/economy " + reason.name(), e);
            }
        });
        return 1;
    }

    private int executeAdminSet(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        long amount = LongArgumentType.getLong(ctx, "amount");
        UUID actorUuid = actorUuid(sender);

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuidOpt = resolveUuid(targetName);
                if (targetUuidOpt.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                UUID targetUuid = targetUuidOpt.get();
                economyService.setBalance(targetUuid, amount, actorUuid);
                auditLogService.record(actorUuid, "ECONOMY_ADMIN_SET", targetUuid, "set to " + formatAmount(amount));
                sync(() -> sender.sendMessage(Component.text(
                        "Set " + targetName + "'s balance to " + formatAmount(amount) + ".", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/economy set", e);
            }
        });
        return 1;
    }

    private int executeTop(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        dbExecutor.submit(() -> {
            try {
                var top = economyService.top(10);
                sync(() -> {
                    sender.sendMessage(Component.text("Top balances:", NamedTextColor.GOLD));
                    int rank = 1;
                    for (var entry : top) {
                        String name = playerNameOrUuid(entry.uuid());
                        sender.sendMessage(Component.text(
                                rank + ". " + name + " - " + formatAmount(entry.balance()), NamedTextColor.YELLOW));
                        rank++;
                    }
                    if (top.isEmpty()) {
                        sender.sendMessage(Component.text("No accounts yet.", NamedTextColor.GRAY));
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/economy top", e);
            }
        });
        return 1;
    }

    // ---- shared helpers ---------------------------------------------------------------

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }

    private static UUID actorUuid(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    /** Resolves an online-or-offline player name to a UUID via {@link PlayerDao} - runs off the main thread. */
    private Optional<UUID> resolveUuid(String name) throws SQLException {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        return playerDao.findByLastKnownName(name).map(PlayerRecord::uuid);
    }

    /** Best-effort display name for a UUID that may not be online - falls back to the raw UUID if unresolvable. */
    private String playerNameOrUuid(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        try {
            return playerDao.findByUuid(uuid).map(PlayerRecord::lastKnownName).orElse(uuid.toString());
        } catch (SQLException e) {
            return uuid.toString();
        }
    }

    private void sendPlayerNotFound(CommandSender sender, String name) {
        sender.sendMessage(Component.text("No player found matching '" + name + "'.", NamedTextColor.RED));
    }

    private void logAndNotifyFailure(CommandSender sender, String commandName, SQLException e) {
        plugin.getLogger().log(Level.WARNING, "Database error handling " + commandName, e);
        sync(() -> sender.sendMessage(Component.text("An internal error occurred. Check the console.", NamedTextColor.RED)));
    }

    private String formatAmount(long amount) {
        return amount + " " + (amount == 1 ? currencyNameSingular() : currencyNamePlural());
    }

    private String currencyNameSingular() {
        return plugin.rigelConfig().economyCurrencyNameSingular();
    }

    private String currencyNamePlural() {
        return plugin.rigelConfig().economyCurrencyNamePlural();
    }

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
