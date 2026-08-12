package org.rigelmc.store;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.util.List;
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
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;
import org.rigelmc.economy.EconomyService;
import org.rigelmc.economy.LedgerReason;
import org.rigelmc.rank.TitleService;

/**
 * {@code /store grant coins|title} - the command surface an external store plugin (Tebex's
 * own "Command Execution" package delivery, or any equivalent) runs on purchase, so a
 * package's config just points at a stable, purpose-built command rather than the general
 * staff-facing {@code /economy give}/{@code /adminconfig}. Console/RCON-only,
 * unconditionally - same security-boundary reasoning as {@code rank.RankAdminModule}'s own
 * {@code /adminconfig}: a player must never be able to run this themselves, since it grants
 * currency/titles with no further gate.
 *
 * <p><b>EULA-compliance note</b> (this project never sells rank/permission tiers - see
 * {@code LICENSE} §1's own "Commercial Use" carve-out for running a monetized server): {@code
 * grant title} can only ever grant an existing, seeded {@link org.rigelmc.rank.Title} -
 * cosmetic prefix only, no permission - never a real {@link org.rigelmc.rank.Rank}. There is
 * deliberately no {@code /store grant rank} command; a store package must never be able to
 * sell staff/permission tiers. {@link org.rigelmc.rank.Title#SUPPORTER} is the seeded title
 * meant for this - see its own javadoc.</p>
 */
public final class StoreModule implements PluginModule {

    private final EconomyService economyService;
    private final TitleService titleService;
    private final PlayerDao playerDao;
    private final AuditLogService auditLogService;
    private final ExecutorService dbExecutor;
    private RigelMCMod plugin;

    public StoreModule(
            @NotNull EconomyService economyService,
            @NotNull TitleService titleService,
            @NotNull PlayerDao playerDao,
            @NotNull AuditLogService auditLogService,
            @NotNull ExecutorService dbExecutor) {
        this.economyService = economyService;
        this.titleService = titleService;
        this.playerDao = playerDao;
        this.auditLogService = auditLogService;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String id() {
        return "store";
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
        registrar.register(storeCommand(), "Console/RCON only - grant a store purchase to a player", List.of());
    }

    private LiteralCommandNode<CommandSourceStack> storeCommand() {
        return Commands.literal("store")
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/store grant coins|title <player> ..."))
                .then(Commands.literal("grant")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/store grant coins|title <player> ..."))
                        .then(Commands.literal("coins")
                                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/store grant coins <player> <amount>"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                                .executes(this::executeGrantCoins))))
                        .then(Commands.literal("title")
                                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/store grant title <player> <titleId>"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("titleId", StringArgumentType.word())
                                                .executes(this::executeGrantTitle)))))
                .build();
    }

    private int executeGrantCoins(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (rejectIfNotConsole(sender)) {
            return 0;
        }
        String targetName = StringArgumentType.getString(ctx, "player");
        long amount = LongArgumentType.getLong(ctx, "amount");

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuidOpt = resolveUuid(targetName);
                if (targetUuidOpt.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                UUID targetUuid = targetUuidOpt.get();
                long newBalance = economyService.credit(targetUuid, amount, LedgerReason.STORE_PURCHASE, "store-grant", null);
                auditLogService.record(
                        null, "STORE_GRANT_COINS", targetUuid, formatAmount(amount) + " -> new balance " + formatAmount(newBalance));
                sync(() -> sender.sendMessage(Component.text(
                        "Granted " + formatAmount(amount) + " to " + targetName + ".", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/store grant coins", e);
            }
        });
        return 1;
    }

    private int executeGrantTitle(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (rejectIfNotConsole(sender)) {
            return 0;
        }
        String targetName = StringArgumentType.getString(ctx, "player");
        String titleId = StringArgumentType.getString(ctx, "titleId");

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuidOpt = resolveUuid(targetName);
                if (targetUuidOpt.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                UUID targetUuid = targetUuidOpt.get();
                if (titleService.title(titleId).isEmpty()) {
                    sync(() -> sender.sendMessage(Component.text(
                            "No title '" + titleId + "' exists - it must already be seeded (see rank.Title).",
                            NamedTextColor.RED)));
                    return;
                }
                titleService.ensureGranted(targetUuid, titleId, null, System.currentTimeMillis());
                auditLogService.record(null, "STORE_GRANT_TITLE", targetUuid, titleId);
                sync(() -> sender.sendMessage(Component.text(
                        "Granted the '" + titleId + "' title to " + targetName + ".", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/store grant title", e);
            }
        });
        return 1;
    }

    // ---- shared helpers ---------------------------------------------------------------

    /** @return {@code true} if the sender was rejected (and already notified); {@code false} if it's console/RCON. */
    private boolean rejectIfNotConsole(CommandSender sender) {
        if (sender instanceof Player) {
            sender.sendMessage(Component.text(
                    "/store can only be run from the server console or RCON - it's meant to be triggered by an"
                            + " external store integration (e.g. Tebex), never by a player.",
                    NamedTextColor.RED));
            return true;
        }
        return false;
    }

    /** Resolves an online-or-offline player name to a UUID via {@link PlayerDao} - runs off the main thread. */
    private Optional<UUID> resolveUuid(String name) throws SQLException {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        return playerDao.findByLastKnownName(name).map(PlayerRecord::uuid);
    }

    private void sendPlayerNotFound(CommandSender sender, String name) {
        sender.sendMessage(Component.text(
                "No player found matching '" + name + "' - they must have joined at least once.", NamedTextColor.RED));
    }

    private void logAndNotifyFailure(CommandSender sender, String commandName, SQLException e) {
        plugin.getLogger().log(Level.WARNING, "Database error handling " + commandName, e);
        sync(() -> sender.sendMessage(Component.text("An internal error occurred. Check the console.", NamedTextColor.RED)));
    }

    private String formatAmount(long amount) {
        RigelConfig config = plugin.rigelConfig();
        return amount + " " + (amount == 1 ? config.economyCurrencyNameSingular() : config.economyCurrencyNamePlural());
    }

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
