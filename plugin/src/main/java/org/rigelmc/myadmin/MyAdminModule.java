package org.rigelmc.myadmin;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import org.rigelmc.command.CommandUsage;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.Rank;
import org.rigelmc.rank.RankService;

/**
 * {@code /myadmin} - a staff member's self-service management of their own admin entry.
 * TFM ref: {@code Command_myadmin.java}, studied directly. Deliberately ports only the
 * login-message subcommands ({@code setlogin}/{@code clearlogin}) - not TFM's {@code
 * clearip}/{@code clearips}, see {@link LoginMessageDao}'s javadoc for why those don't
 * have a safe RigelMCMod equivalent (they'd punch a hole in the actual anti-ban-evasion
 * mechanism, not just tidy up an admin-panel convenience list like in TFM). Adds {@code
 * info} (not in TFM) to round out "manage/view my own entry" given the IP subcommands
 * were intentionally left out.
 *
 * <p>The login message itself is shown as an extra line on this player's own staff-join
 * announcement - see {@code core.PlayerLoginListener}.</p>
 */
public final class MyAdminModule implements PluginModule {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final LoginMessageDao loginMessageDao;
    private final PlayerDao playerDao;
    private final RankService rankService;
    private final PermissionGate permissionGate;
    private final ExecutorService dbExecutor;
    private RigelMCMod plugin;

    public MyAdminModule(
            @NotNull LoginMessageDao loginMessageDao,
            @NotNull PlayerDao playerDao,
            @NotNull RankService rankService,
            @NotNull PermissionGate permissionGate,
            @NotNull ExecutorService dbExecutor) {
        this.loginMessageDao = loginMessageDao;
        this.playerDao = playerDao;
        this.rankService = rankService;
        this.permissionGate = permissionGate;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String id() {
        return "myadmin";
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
        registrar.register(myAdminCommand(), "Manage your own admin entry (login message, info) - Moderator+");
    }

    private LiteralCommandNode<CommandSourceStack> myAdminCommand() {
        return Commands.literal("myadmin")
                .requires(source -> hasRank(source, "moderator") && source.getSender() instanceof Player)
                .executes(ctx -> CommandUsage.show(
                        ctx.getSource().getSender(), "/myadmin <setlogin <message>|clearlogin|info>"))
                .then(Commands.literal("setlogin")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/myadmin setlogin <message>"))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(this::executeSetLogin)))
                .then(Commands.literal("clearlogin").executes(this::executeClearLogin))
                .then(Commands.literal("info").executes(this::executeInfo))
                .build();
    }

    // ---- /myadmin setlogin <message> -----------------------------------------------------

    private int executeSetLogin(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        String rawMessage = StringArgumentType.getString(ctx, "message");
        UUID uuid = player.getUniqueId();

        dbExecutor.submit(() -> {
            try {
                loginMessageDao.upsert(uuid, rawMessage, System.currentTimeMillis());
                // & (legacy) color codes via chat.ColorCodes, not full MiniMessage parsing -
                // matches tag.TagService's own precedent for player-typed formatted text,
                // so a player can't smuggle a click/hover event into a broadcast everyone
                // sees.
                Component preview = org.rigelmc.chat.ColorCodes.parse(rawMessage);
                sync(() -> player.sendMessage(Component.text("Your login message is now:", NamedTextColor.GRAY)
                        .append(Component.newline())
                        .append(Component.text("  > ", NamedTextColor.DARK_GRAY))
                        .append(preview)));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to process /myadmin setlogin", e);
                sync(() -> player.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    // ---- /myadmin clearlogin --------------------------------------------------------------

    private int executeClearLogin(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        UUID uuid = player.getUniqueId();

        dbExecutor.submit(() -> {
            try {
                boolean removed = loginMessageDao.delete(uuid);
                sync(() -> player.sendMessage(Component.text(
                        removed ? "Your login message has been removed." : "You didn't have a login message set.",
                        NamedTextColor.GRAY)));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to process /myadmin clearlogin", e);
                sync(() -> player.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    // ---- /myadmin info ----------------------------------------------------------------------

    private int executeInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        dbExecutor.submit(() -> {
            try {
                Optional<PlayerRecord> record = playerDao.findByUuid(uuid);
                Optional<String> loginMessage = loginMessageDao.find(uuid);
                sync(() -> sender.sendMessage(buildInfoMessage(record, loginMessage)));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to process /myadmin info", e);
                sync(() -> sender.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    private Component buildInfoMessage(Optional<PlayerRecord> record, Optional<String> loginMessage) {
        Rank rank = rankService.rank(record.map(PlayerRecord::rankId).orElse("default"))
                .orElseGet(rankService::defaultRank);

        Component message = Component.text("Your admin entry:", NamedTextColor.AQUA)
                .append(Component.newline())
                .append(Component.text("  Rank: " + rank.displayName(), NamedTextColor.GRAY));
        if (record.isPresent()) {
            String firstSeen = DATE_FORMAT.format(Instant.ofEpochMilli(record.get().firstSeenAt()));
            message = message.append(Component.newline())
                    .append(Component.text("  First seen: " + firstSeen, NamedTextColor.GRAY));
        }
        message = message.append(Component.newline())
                .append(Component.text(
                        "  Login message: " + loginMessage.orElse("(none set - /myadmin setlogin <message>)"),
                        NamedTextColor.GRAY));
        return message;
    }

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }
}
