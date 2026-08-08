package org.rigelmc.punish;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.command.CommandFlags;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.command.EssentialsCollisionPolicy;
import org.rigelmc.command.PlayerSuggestions;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.dao.IpHistoryDao;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;
import org.rigelmc.identity.IpHasher;
import org.rigelmc.punish.ban.Ban;
import org.rigelmc.punish.ban.BanService;
import org.rigelmc.punish.ban.IpAddressUtil;
import org.rigelmc.punish.cage.CageListener;
import org.rigelmc.punish.cage.CageService;
import org.rigelmc.punish.freeze.FreezeListener;
import org.rigelmc.punish.freeze.FreezeService;
import org.rigelmc.punish.mute.MuteService;
import org.rigelmc.punish.warn.StrikeService;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.rank.Rank;
import org.rigelmc.rank.RankService;
import org.rigelmc.rollback.CoreProtectBridge;

/**
 * Registers {@code /ban}, {@code /tban} (alias {@code /tempban}), {@code /permban}, and
 * {@code /punish unban|mute|unmute} - see docs/architecture.md "Ban system: /ban, /tban,
 * /permban cascade, flags, web panel" for the full design this implements. {@code /mute}
 * and {@code /unmute} additionally register as standalone Essentials-name-shadowing
 * aliases of {@code /punish mute|unmute} when {@link EssentialsCollisionPolicy} allows it.
 * Also owns {@code /wipecoreprotect} - a Senior Admin+, two-step-confirmed command that
 * wipes CoreProtect's entire logging/rollback database via {@link CoreProtectBridge},
 * grouped here since this module already holds the only {@link CoreProtectBridge}
 * instance (used elsewhere for {@code /ban}/{@code /tban}'s auto-rollback).
 */
public final class PunishModule implements PluginModule {

    /** Fixed duration for {@code /mute} - TFM's own {@code /stfu}/{@code Muter} timeout. */
    private static final long FIVE_MINUTES_MILLIS = 5L * 60L * 1000L;

    private final BanService banService;
    private final MuteService muteService;
    private final PlayerDao playerDao;
    private final IpHistoryDao ipHistoryDao;
    private final IpHasher ipHasher;
    private final CoreProtectBridge coreProtectBridge;
    private final PermissionGate permissionGate;
    private final RankService rankService;
    private final FreezeService freezeService;
    private final CageService cageService;
    private final StrikeService strikeService;
    private final AuditLogService auditLogService;
    private final ExecutorService dbExecutor;
    private RigelMCMod plugin;

    public PunishModule(
            @NotNull BanService banService,
            @NotNull MuteService muteService,
            @NotNull PlayerDao playerDao,
            @NotNull IpHistoryDao ipHistoryDao,
            @NotNull IpHasher ipHasher,
            @NotNull CoreProtectBridge coreProtectBridge,
            @NotNull PermissionGate permissionGate,
            @NotNull RankService rankService,
            @NotNull FreezeService freezeService,
            @NotNull CageService cageService,
            @NotNull StrikeService strikeService,
            @NotNull AuditLogService auditLogService,
            @NotNull ExecutorService dbExecutor) {
        this.banService = banService;
        this.muteService = muteService;
        this.playerDao = playerDao;
        this.ipHistoryDao = ipHistoryDao;
        this.ipHasher = ipHasher;
        this.coreProtectBridge = coreProtectBridge;
        this.permissionGate = permissionGate;
        this.rankService = rankService;
        this.freezeService = freezeService;
        this.cageService = cageService;
        this.strikeService = strikeService;
        this.auditLogService = auditLogService;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String id() {
        return "punish";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new FreezeListener(freezeService, permissionGate), plugin);
        cageService.loadPersistedState();
        plugin.getServer().getPluginManager().registerEvents(new CageListener(cageService), plugin);
    }

    @Override
    public void contributeCommands(Commands registrar, EssentialsCollisionPolicy collisionPolicy) {
        registrar.register(banCommand(),
                "Ban a player for 24 hours, with automatic CoreProtect rollback", List.of("gtfo"));
        registrar.register(tbanCommand(), "Temp-ban a player for a custom duration", List.of("tempban", "noob"));
        registrar.register(permbanCommand(), "Permanently ban a name or IP, cascading to every known alias");
        registrar.register(punishCommand(), "Punishment admin commands (unban, mute, unmute, ...)");
        registrar.register(freezeCommand(), "Freeze a player, or toggle a global freeze - Senior Admin+", List.of("fr"));
        registrar.register(freezeallCommand(), "Toggle a global freeze on every online player - Senior Admin+");
        registrar.register(smiteCommand(), "Deop, clear, strike lightning, and kill a player - Senior Admin+");
        registrar.register(warnCommand(), "Warn a player, escalating through the ban system on repeat offenses - Moderator+");
        registrar.register(cageCommand(), "Toggle a block cage around a player - Senior Admin+");
        // Unconditional, not gated behind collisionPolicy - deliberately shadows
        // Essentials' own /unban, exactly like /ban already shadows Essentials' /ban (see
        // the naming table in docs/architecture.md). Lifts a ban regardless of which
        // command originally created it (/ban, /tban, or /permban all write to the same
        // rigel_bans table), so this one command covers all three.
        registrar.register(unbanCommand(), "Lift a ban issued by /ban, /tban, or /permban");

        // /mute and /unmute are unconditional, not gated behind collisionPolicy -
        // deliberately shadow Essentials' own /mute, exactly like /ban and /unban already
        // do (see the naming table in docs/architecture.md). /mute is TFM's /stfu, not
        // /punish mute: a quick, public, fixed 5-minute toggle-mute rather than an
        // indefinite staff action - see executeQuickMute's javadoc for why both exist.
        registrar.register(muteCommand(), "Toggle-mute a player for 5 minutes, with a public announcement");
        registrar.register(unmuteCommand(), "Lift a mute issued by /mute or /punish mute");

        // Unconditional, not gated behind collisionPolicy - deliberately shadows Essentials'
        // own /kick, exactly like /ban and /mute already do (see the naming table in
        // docs/architecture.md).
        registrar.register(kickCommand(), "Kick an online player, with a public announcement - Moderator+");
        registrar.register(wipeCoreProtectCommand(),
                "Permanently wipe CoreProtect's entire logging/rollback database - Senior Admin+");
    }

    // ---- /ban ----------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> banCommand() {
        return Commands.literal("ban")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/ban <player> [reason] [-s] [--force-staff]"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> executeBan(ctx, ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeBan(ctx, StringArgumentType.getString(ctx, "reason")))))
                .build();
    }

    private int executeBan(CommandContext<CommandSourceStack> ctx, String reasonAndFlags) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        UUID actorUuid = actorUuid(sender);
        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        CommandFlags flags = CommandFlags.parse(reasonAndFlags);
        String reason = flags.remainder().isBlank() ? "Banned by " + sender.getName() : flags.remainder();
        boolean silent = flags.has("s", "silent");
        boolean forceStaff = flags.has("force-staff");

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> uuidOpt = resolveUuid(targetName, onlineTarget);
                if (uuidOpt.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                if (actorUuid != null
                        && isBlockedByStaffProtection(rankService.rankOf(actorUuid), rankService.rankOf(uuidOpt.get()), forceStaff)) {
                    sync(() -> sendStaffProtectionDenied(sender, targetName));
                    return;
                }
                long now = System.currentTimeMillis();
                Duration duration = plugin.rigelConfig().banDefaultDuration();
                String targetIpHash = resolveIpHashForBan(uuidOpt.get(), onlineTarget);
                List<Ban> created = banService.banByNameAndIp(
                        uuidOpt.get(), targetName, targetIpHash, reason, actorUuid, now, now + duration.toMillis());
                boolean alsoBannedIp = created.size() > 1;

                if (coreProtectBridge.isAvailable()) {
                    coreProtectBridge.rollbackPlayer(targetName, plugin.rigelConfig().banAutoRollbackLookback());
                }

                sync(() -> {
                    announcePunishment(sender, silent, "ban.public",
                            "<gold>{target} was banned for 24h{ipnote}. Reason: {reason}",
                            "target", targetName, "reason", reason,
                            "ipnote", alsoBannedIp ? " (IP also banned)" : "");
                    if (onlineTarget != null) {
                        onlineTarget.kick(Component.text(
                                "You have been banned for 24 hours.\nReason: " + reason, NamedTextColor.RED));
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/ban", e);
            }
        });
        return 1;
    }

    // ---- /tban ----------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> tbanCommand() {
        String usage = "/tban <player> <duration> [reason] [-s] [-r] [--force-staff]";
        return Commands.literal("tban")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), usage))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), usage))
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(ctx -> executeTban(ctx, ""))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx ->
                                                executeTban(ctx, StringArgumentType.getString(ctx, "reason"))))))
                .build();
    }

    private int executeTban(CommandContext<CommandSourceStack> ctx, String reasonAndFlags) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        String durationInput = StringArgumentType.getString(ctx, "duration");
        UUID actorUuid = actorUuid(sender);
        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        CommandFlags flags = CommandFlags.parse(reasonAndFlags);
        String reason = flags.remainder().isBlank() ? "Temp-banned by " + sender.getName() : flags.remainder();
        boolean silent = flags.has("s", "silent");
        boolean rollback = flags.has("r", "rollback");
        boolean forceStaff = flags.has("force-staff");

        Optional<Duration> duration = DurationParser.parse(durationInput);
        if (duration.isEmpty()) {
            sender.sendMessage(Component.text(
                    "Invalid duration '" + durationInput + "'. Use e.g. 1d, 2h30m, 45m.", NamedTextColor.RED));
            return 0;
        }

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> uuidOpt = resolveUuid(targetName, onlineTarget);
                if (uuidOpt.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                if (actorUuid != null
                        && isBlockedByStaffProtection(rankService.rankOf(actorUuid), rankService.rankOf(uuidOpt.get()), forceStaff)) {
                    sync(() -> sendStaffProtectionDenied(sender, targetName));
                    return;
                }
                long now = System.currentTimeMillis();
                banService.tempBanByName(
                        uuidOpt.get(), targetName, reason, actorUuid, now, now + duration.get().toMillis());

                if (rollback && coreProtectBridge.isAvailable()) {
                    coreProtectBridge.rollbackPlayer(targetName, plugin.rigelConfig().tbanRollbackLookback());
                }

                sync(() -> {
                    announcePunishment(
                            sender, silent, "tban.public", "<gold>{target} was banned for {duration}. Reason: {reason}",
                            "target", targetName, "duration", durationInput, "reason", reason);
                    if (onlineTarget != null) {
                        onlineTarget.kick(Component.text(
                                "You have been banned for " + durationInput + ".\nReason: " + reason,
                                NamedTextColor.RED));
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/tban", e);
            }
        });
        return 1;
    }

    // ---- /permban ---------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> permbanCommand() {
        return Commands.literal("permban")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/permban <name|ip> [reason] [-s] [--force-staff]"))
                .then(Commands.argument("target", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> executePermban(ctx, ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executePermban(ctx, StringArgumentType.getString(ctx, "reason")))))
                .build();
    }

    private int executePermban(CommandContext<CommandSourceStack> ctx, String reasonAndFlags) {
        CommandSender sender = ctx.getSource().getSender();
        String target = StringArgumentType.getString(ctx, "target");
        UUID actorUuid = actorUuid(sender);
        CommandFlags flags = CommandFlags.parse(reasonAndFlags);
        String reason = flags.remainder().isBlank() ? "Permanently banned by " + sender.getName() : flags.remainder();
        boolean silent = flags.has("s", "silent");
        boolean forceStaff = flags.has("force-staff");
        boolean isIp = IpAddressUtil.looksLikeIp(target);
        String ipHash = isIp ? ipHasher.hash(target) : null;

        dbExecutor.submit(() -> {
            try {
                // Staff-target protection only applies to the direct name-based target -
                // an IP-based /permban can cascade to multiple accounts, and pre-checking
                // "is any of them staff" before the cascade even runs is out of scope for
                // this pass (see PART 3's own scoping notes for the same "verified,
                // lower-risk first" principle). /permban is Senior Admin+ only already,
                // so against a name target this naturally also means a Senior Admin can
                // never be permban'd by a peer at all - nothing outranks the top rank.
                if (!isIp && actorUuid != null) {
                    Optional<PlayerRecord> targetRecord = playerDao.findByLastKnownName(target);
                    if (targetRecord.isPresent()
                            && isBlockedByStaffProtection(
                                    rankService.rankOf(actorUuid), rankService.rankOf(targetRecord.get().uuid()), forceStaff)) {
                        sync(() -> sendStaffProtectionDenied(sender, target));
                        return;
                    }
                }
                long now = System.currentTimeMillis();
                List<Ban> cascade = banService.permban(target, ipHash, reason, actorUuid, now);

                if (isIp) {
                    for (Ban ban : cascade) {
                        if (ban.targetUuid() != null) {
                            Player online = Bukkit.getPlayer(ban.targetUuid());
                            if (online != null) {
                                sync(() -> online.kick(Component.text(
                                        "You have been permanently banned.\nReason: " + reason,
                                        NamedTextColor.RED)));
                            }
                        }
                    }
                } else {
                    Player online = Bukkit.getPlayerExact(target);
                    if (online != null) {
                        sync(() -> online.kick(
                                Component.text("You have been permanently banned.\nReason: " + reason, NamedTextColor.RED)));
                    }
                }

                int names = (int) cascade.stream().filter(b -> b.targetUuid() != null || b.targetLastName() != null).count();
                int ips = cascade.size() - names;
                sync(() -> announcePunishment(
                        sender, silent, "permban.public",
                        "<gold>Permanently banned '{target}' - cascade covered {names} name(s) and {ips} IP(s)."
                                + " Reason: {reason}",
                        "target", target, "names", String.valueOf(names), "ips", String.valueOf(ips),
                        "reason", reason));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/permban", e);
            }
        });
        return 1;
    }

    // ---- /freeze, /freezeall, /smite ---------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> freezeCommand() {
        return Commands.literal("freeze")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(ctx -> executeToggleGlobalFreeze(ctx))
                .then(Commands.literal("on").executes(ctx -> executeSetGlobalFreeze(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> executeSetGlobalFreeze(ctx, false)))
                .then(Commands.literal("purge").executes(this::executeFreezePurge))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> executeTogglePlayerFreeze(ctx))
                        .then(Commands.literal("on").executes(ctx -> executeSetPlayerFreeze(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> executeSetPlayerFreeze(ctx, false)))
                        .then(Commands.literal("hard")
                                .executes(this::executeToggleHardFreeze)
                                .then(Commands.literal("on").executes(ctx -> executeSetHardFreeze(ctx, true)))
                                .then(Commands.literal("off").executes(ctx -> executeSetHardFreeze(ctx, false)))))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> freezeallCommand() {
        return Commands.literal("freezeall")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(this::executeToggleGlobalFreeze)
                .build();
    }

    private int executeToggleGlobalFreeze(CommandContext<CommandSourceStack> ctx) {
        return executeSetGlobalFreeze(ctx, !freezeService.isGlobalFreeze());
    }

    private int executeSetGlobalFreeze(CommandContext<CommandSourceStack> ctx, boolean value) {
        CommandSender sender = ctx.getSource().getSender();
        freezeService.setGlobalFreeze(value, actorUuid(sender));

        broadcastPublic("freeze.global-toggle", "<red>{sender} {state} global player freeze.",
                "sender", sender.getName(), "state", value ? "enabled" : "disabled");
        if (value) {
            Component frozenMessage = plugin.messages().get("freeze.global-target",
                    "<red>You have been temporarily frozen due to rulebreakers. You will be unfrozen soon.");
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!permissionGate.hasAtLeastCached(online.getUniqueId(), "moderator")) {
                    online.sendMessage(frozenMessage);
                }
            }
        }
        sender.sendMessage(plugin.messages().get("freeze.global-confirm", "<gray>Players are now {state}.",
                "state", value ? "frozen" : "free to move"));
        return 1;
    }

    private int executeFreezePurge(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        freezeService.purge(actorUuid(sender));
        broadcastPublic("freeze.purge", "<red>{sender} unfroze every player.", "sender", sender.getName());
        return 1;
    }

    private int executeTogglePlayerFreeze(CommandContext<CommandSourceStack> ctx) {
        Player target = onlineTargetOrFail(ctx, "player");
        if (target == null) {
            return 0;
        }
        return executeSetPlayerFreezeFor(ctx, target, !freezeService.isFrozen(target.getUniqueId()));
    }

    private int executeSetPlayerFreeze(CommandContext<CommandSourceStack> ctx, boolean value) {
        Player target = onlineTargetOrFail(ctx, "player");
        return target == null ? 0 : executeSetPlayerFreezeFor(ctx, target, value);
    }

    private int executeSetPlayerFreezeFor(CommandContext<CommandSourceStack> ctx, Player target, boolean value) {
        CommandSender sender = ctx.getSource().getSender();
        if (value) {
            freezeService.freeze(target, actorUuid(sender));
        } else {
            freezeService.unfreeze(target, actorUuid(sender));
        }
        broadcastPublic("freeze.individual", "<red>{sender} {action} {target}.",
                "sender", sender.getName(), "action", value ? "froze" : "unfroze", "target", target.getName());
        target.sendMessage(plugin.messages().get(
                value ? "freeze.individual-target-on" : "freeze.individual-target-off",
                value ? "<aqua>You have been frozen." : "<aqua>You have been unfrozen."));
        return 1;
    }

    private int executeToggleHardFreeze(CommandContext<CommandSourceStack> ctx) {
        Player target = onlineTargetOrFail(ctx, "player");
        if (target == null) {
            return 0;
        }
        return executeSetHardFreezeFor(ctx, target, !freezeService.isHardFrozen(target.getUniqueId()));
    }

    private int executeSetHardFreeze(CommandContext<CommandSourceStack> ctx, boolean value) {
        Player target = onlineTargetOrFail(ctx, "player");
        return target == null ? 0 : executeSetHardFreezeFor(ctx, target, value);
    }

    /**
     * TFM's real {@code /lockup} replacement (see {@code punish.freeze.FreezeService}'s own
     * javadoc for why it isn't a straight port) - a modifier on top of an ordinary freeze,
     * not a separate command. Freezes the target first if they aren't already, so "hard-lock
     * a griefer" is one command rather than two.
     */
    private int executeSetHardFreezeFor(CommandContext<CommandSourceStack> ctx, Player target, boolean value) {
        CommandSender sender = ctx.getSource().getSender();
        UUID actor = actorUuid(sender);
        if (value && !freezeService.isFrozen(target.getUniqueId())) {
            freezeService.freeze(target, actor);
        }
        freezeService.setHardFrozen(target.getUniqueId(), value);
        broadcastPublic("freeze.hard", "<red>{sender} {action} hard-freeze on {target}.",
                "sender", sender.getName(), "action", value ? "enabled" : "disabled", "target", target.getName());
        target.sendMessage(plugin.messages().get(
                value ? "freeze.hard-target-on" : "freeze.hard-target-off",
                value
                        ? "<aqua>You have been frozen and cannot chat, use commands, or interact."
                        : "<aqua>Hard-freeze lifted."));
        return 1;
    }

    private LiteralCommandNode<CommandSourceStack> smiteCommand() {
        return Commands.literal("smite")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/smite <player> [reason]"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> executeSmite(ctx, null))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeSmite(ctx, StringArgumentType.getString(ctx, "reason")))))
                .build();
    }

    /** TFM ref: {@code Command_smite} - deop, gamemode survival, clear inventory, 3x3 lightning strike, kill. */
    private int executeSmite(CommandContext<CommandSourceStack> ctx, String reason) {
        Player target = onlineTargetOrFail(ctx, "player");
        if (target == null) {
            return 0;
        }

        broadcastPublic("smite.public", "<red>{target} has been a naughty, naughty boy.",
                "target", target.getName());
        if (reason != null) {
            Component reasonMessage = plugin.messages().get(
                    "smite.reason", "<yellow>  Reason: {reason}", "reason", reason);
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(reasonMessage);
            }
        }

        target.setOp(false);
        target.setGameMode(GameMode.SURVIVAL);
        target.getInventory().clear();

        Location center = target.getLocation();
        World world = target.getWorld();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                world.strikeLightning(new Location(
                        world, center.getBlockX() + x, center.getBlockY(), center.getBlockZ() + z));
            }
        }
        target.setHealth(0.0);

        String reasonSuffix = reason != null ? "\nReason: " + reason : "";
        target.sendMessage(plugin.messages().get(
                "smite.target", "<red>You have been smitten.{reason}", "reason", reasonSuffix));
        return 1;
    }

    // ---- /warn --------------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> warnCommand() {
        return Commands.literal("warn")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/warn <player> [reason]"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> executeWarn(ctx, null))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeWarn(ctx, StringArgumentType.getString(ctx, "reason")))))
                .build();
    }

    /**
     * Records a time-decayed strike ({@link StrikeService}) and, once it reaches a
     * configured threshold ({@code punish.strikes.thresholds}), escalates through the
     * existing {@link #banService} - see {@link StrikeService}'s javadoc for why this
     * connects end-to-end unlike TFM's own disconnected {@code /warn}/{@code AutoEject}.
     */
    private int executeWarn(CommandContext<CommandSourceStack> ctx, @Nullable String reason) {
        Player target = onlineTargetOrFail(ctx, "player");
        if (target == null) {
            return 0;
        }
        CommandSender sender = ctx.getSource().getSender();
        UUID actorUuid = actorUuid(sender);
        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName();
        String reasonText = reason != null ? reason : "No reason given";

        dbExecutor.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                int strikeCount =
                        strikeService.recordStrike(targetUuid, plugin.rigelConfig().strikeDecayHours(), now);
                auditLogService.record(actorUuid, "WARN", targetUuid, "strike " + strikeCount + ": " + reasonText);

                sync(() -> {
                    broadcastPublic("warn.public", "<gold>{target} was warned (strike {strike}). Reason: {reason}",
                            "target", targetName, "strike", String.valueOf(strikeCount), "reason", reasonText);
                    if (target.isOnline()) {
                        target.sendMessage(plugin.messages().get("warn.target",
                                "<red>You have been warned (strike {strike}).\n<yellow>Reason: {reason}",
                                "strike", String.valueOf(strikeCount), "reason", reasonText));
                    }
                });

                Long escalationHours = floorThreshold(plugin.rigelConfig().strikeThresholds(), strikeCount);
                if (escalationHours != null) {
                    applyStrikeEscalation(target, targetUuid, targetName, strikeCount, escalationHours, now);
                }
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/warn", e);
            }
        });
        return 1;
    }

    /** @return the configured value for the largest threshold key {@code <= strikeCount}, or {@code null} if none. */
    @Nullable
    private static Long floorThreshold(java.util.TreeMap<Integer, Long> thresholds, int strikeCount) {
        var entry = thresholds.floorEntry(strikeCount);
        return entry != null ? entry.getValue() : null;
    }

    /**
     * Runs on {@link #dbExecutor} already (called from {@link #executeWarn}) - the ban-
     * service calls here are more blocking DB I/O, consistent with that. Only the final
     * kick/broadcast hop back to the main thread via {@link #sync}.
     */
    private void applyStrikeEscalation(
            Player target, UUID targetUuid, String targetName, int strikeCount, long durationHours, long now)
            throws SQLException {
        String reason = "Automatic escalation - reached strike " + strikeCount;
        if (durationHours == 0) {
            sync(() -> {
                if (target.isOnline()) {
                    target.kick(plugin.messages().get(
                            "warn.escalation-target", "<red>You have been {action} - strike {strike}.",
                            "action", "kicked", "strike", String.valueOf(strikeCount)));
                }
                broadcastPublic("warn.escalation-public",
                        "<gold>{target} was automatically {action} after reaching strike {strike}.",
                        "target", targetName, "action", "kicked", "strike", String.valueOf(strikeCount));
            });
            return;
        }

        String action = durationHours < 0 ? "permanently banned" : "banned";
        if (durationHours < 0) {
            banService.permban(targetName, null, reason, null, now);
        } else {
            long expiresAt = now + Duration.ofHours(durationHours).toMillis();
            banService.tempBanByName(targetUuid, targetName, reason, null, now, expiresAt);
        }
        sync(() -> {
            if (target.isOnline()) {
                target.kick(plugin.messages().get(
                        "warn.escalation-target", "<red>You have been {action} - strike {strike}.",
                        "action", action, "strike", String.valueOf(strikeCount)));
            }
            broadcastPublic("warn.escalation-public",
                    "<gold>{target} was automatically {action} after reaching strike {strike}.",
                    "target", targetName, "action", action, "strike", String.valueOf(strikeCount));
        });
    }

    // ---- /kick --------------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> kickCommand() {
        return Commands.literal("kick")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/kick <player> [reason] [-s] [--force-staff]"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> executeKick(ctx, ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeKick(ctx, StringArgumentType.getString(ctx, "reason")))))
                .build();
    }

    /**
     * A pure kick - no ban record created, distinct from {@code /tban}. Public by default
     * (matches every other punishment command's {@code adminAction} broadcast convention),
     * suppressible via {@code -s}/{@code --silent} like {@code /ban}.
     */
    private int executeKick(CommandContext<CommandSourceStack> ctx, String reasonAndFlags) {
        Player target = onlineTargetOrFail(ctx, "player");
        if (target == null) {
            return 0;
        }
        CommandSender sender = ctx.getSource().getSender();
        UUID actorUuid = actorUuid(sender);
        CommandFlags flags = CommandFlags.parse(reasonAndFlags);
        String reason = flags.remainder().isBlank() ? "Kicked by " + sender.getName() : flags.remainder();
        boolean silent = flags.has("s", "silent");
        boolean forceStaff = flags.has("force-staff");
        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName();

        // Both sides guaranteed online here (onlineTargetOrFail above; console bypasses
        // via the actorUuid == null check) - see onlineApproxRank's javadoc for why the
        // fast cache-only lookup is safe to use for this command specifically.
        if (actorUuid != null
                && isBlockedByStaffProtection(onlineApproxRank(actorUuid), onlineApproxRank(targetUuid), forceStaff)) {
            sendStaffProtectionDenied(sender, targetName);
            return 0;
        }

        target.kick(plugin.messages().get(
                "kick.target", "<red>You have been kicked.\n<yellow>Reason: {reason}", "reason", reason));
        announcePunishment(sender, silent, "kick.public", "<gold>{target} was kicked. Reason: {reason}",
                "target", targetName, "reason", reason);

        dbExecutor.submit(() -> {
            try {
                auditLogService.record(actorUuid, "KICK", targetUuid, reason);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to audit-log /kick of " + targetName, e);
            }
        });
        return 1;
    }

    // ---- /wipecoreprotect ----------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> wipeCoreProtectCommand() {
        return Commands.literal("wipecoreprotect")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(this::executeWipeCoreProtectWarn)
                .then(Commands.literal("confirm").executes(this::executeWipeCoreProtectConfirmed))
                .build();
    }

    /**
     * {@code /wipecoreprotect} (bare) - warns only, performs no wipe. Two-step
     * confirmation, same pattern as {@code /adminconfig reset confirm} in {@code
     * rank.RankAdminModule} - unlike an admin-rank reset, which can just be redone,
     * CoreProtect's database is the server's entire grief-rollback history; once purged
     * it's gone forever, including this session's own {@code /ban}/{@code /tban}
     * auto-rollback ability for anything that happened before this point.
     */
    private int executeWipeCoreProtectWarn(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!coreProtectBridge.isAvailable()) {
            sender.sendMessage(Component.text(
                    "CoreProtect isn't installed (or isn't ready) on this server - nothing to wipe.",
                    NamedTextColor.RED));
            return 0;
        }
        sender.sendMessage(Component.text(
                "This will PERMANENTLY erase CoreProtect's entire logging/rollback database - every block"
                        + " change, container transaction, and chat/command log it has ever recorded. This"
                        + " cannot be undone, and no future /ban or /tban will be able to auto-rollback"
                        + " anything that happened before this point. Run '/wipecoreprotect confirm' to"
                        + " proceed.",
                NamedTextColor.RED));
        return 1;
    }

    /** {@code /wipecoreprotect confirm} - the actual wipe. See {@link CoreProtectBridge#purgeAll}. */
    private int executeWipeCoreProtectConfirmed(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!coreProtectBridge.isAvailable()) {
            sender.sendMessage(Component.text(
                    "CoreProtect isn't installed (or isn't ready) on this server - nothing to wipe.",
                    NamedTextColor.RED));
            return 0;
        }

        coreProtectBridge.purgeAll(plugin);
        sender.sendMessage(Component.text(
                "Wiping CoreProtect's database now - check console shortly to confirm it completed.",
                NamedTextColor.GOLD));

        UUID actorUuid = actorUuid(sender);
        String actorName = sender.getName();
        Component staffNotice = Component.text(
                actorName + " wiped CoreProtect's entire database. Rollback history before this point is"
                        + " gone.", NamedTextColor.RED);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(sender) && permissionGate.hasAtLeastCached(online.getUniqueId(), "moderator")) {
                online.sendMessage(staffNotice);
            }
        }
        plugin.getLogger().warning(actorName + " ran /wipecoreprotect confirm - wiping the CoreProtect database.");

        dbExecutor.submit(() -> {
            try {
                auditLogService.record(actorUuid, "COREPROTECT_WIPE", null, "wiped the CoreProtect database");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to audit-log /wipecoreprotect", e);
            }
        });
        return 1;
    }

    // ---- /cage --------------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> cageCommand() {
        return Commands.literal("cage")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(ctx -> CommandUsage.show(
                        ctx.getSource().getSender(), "/cage <player> [outer_material inner_material] | purge"))
                .then(Commands.literal("purge").executes(this::executeCagePurge))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(this::executeCageToggle)
                        .then(Commands.argument("outer", StringArgumentType.word())
                                .then(Commands.argument("inner", StringArgumentType.word())
                                        .executes(this::executeCageWithMaterials))))
                .build();
    }

    /** {@code /cage <player>} - toggles the default glass/air cage on or off. */
    private int executeCageToggle(CommandContext<CommandSourceStack> ctx) {
        Player target = onlineTargetOrFail(ctx, "player");
        if (target == null) {
            return 0;
        }
        CommandSender sender = ctx.getSource().getSender();
        if (cageService.isCaged(target.getUniqueId())) {
            cageService.uncage(target);
            announceCage(sender, target, false);
        } else {
            cageService.cage(target, actorUuid(sender), org.bukkit.Material.GLASS, org.bukkit.Material.AIR);
            announceCage(sender, target, true);
        }
        return 1;
    }

    /**
     * {@code /cage <player> <outer> <inner>} - always (re-)cages with the given materials,
     * matching TFM: no toggle-off path on this overload, {@link CageService#cage} already
     * handles "was already caged" by regenerating in place at the new materials/location.
     */
    private int executeCageWithMaterials(CommandContext<CommandSourceStack> ctx) {
        Player target = onlineTargetOrFail(ctx, "player");
        if (target == null) {
            return 0;
        }
        CommandSender sender = ctx.getSource().getSender();
        org.bukkit.Material outer = parseMaterialArgOrFail(sender, StringArgumentType.getString(ctx, "outer"));
        if (outer == null) {
            return 0;
        }
        org.bukkit.Material inner = parseMaterialArgOrFail(sender, StringArgumentType.getString(ctx, "inner"));
        if (inner == null) {
            return 0;
        }
        cageService.cage(target, actorUuid(sender), outer, inner);
        announceCage(sender, target, true);
        return 1;
    }

    private int executeCagePurge(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        cageService.purgeAll(Bukkit.getOnlinePlayers());
        broadcastPublic("cage.purge", "<red>{sender} uncaged every caged player.", "sender", sender.getName());
        return 1;
    }

    private void announceCage(CommandSender sender, Player target, boolean caged) {
        broadcastPublic("cage.toggle", "<red>{sender} {action} {target}.",
                "sender", sender.getName(), "action", caged ? "caged" : "uncaged", "target", target.getName());
        target.sendMessage(plugin.messages().get(
                caged ? "cage.target-on" : "cage.target-off",
                caged ? "<red>You have been caged." : "<green>You have been released from your cage."));
    }

    @Nullable
    private org.bukkit.Material parseMaterialArgOrFail(CommandSender sender, String raw) {
        try {
            return org.bukkit.Material.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Unknown material '" + raw + "'.", NamedTextColor.RED));
            return null;
        }
    }

    // ---- /punish unban ---------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> punishCommand() {
        return Commands.literal("punish")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(
                        ctx.getSource().getSender(), "/punish unban|mute|unmute <player> ..."))
                .then(Commands.literal("unban")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/punish unban <name|ip> [-c]"))
                        .then(Commands.argument("target", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(ctx -> executeUnban(ctx, false))
                                .then(Commands.literal("-c").executes(ctx -> executeUnban(ctx, true)))
                                .then(Commands.literal("--case").executes(ctx -> executeUnban(ctx, true)))))
                .then(Commands.literal("mute")
                        .executes(ctx -> CommandUsage.show(
                                ctx.getSource().getSender(), "/punish mute <player> [reason] [-s]"))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(ctx -> executeMute(ctx, ""))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx ->
                                                executeMute(ctx, StringArgumentType.getString(ctx, "reason"))))))
                .then(Commands.literal("unmute")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/punish unmute <player>"))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(this::executeUnmute)))
                .build();
    }

    // ---- /unban (Essentials-name-shadowing alias of /punish unban) -------------------

    private LiteralCommandNode<CommandSourceStack> unbanCommand() {
        return Commands.literal("unban")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/unban <name|ip> [-c]"))
                .then(Commands.argument("target", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> executeUnban(ctx, false))
                        .then(Commands.literal("-c").executes(ctx -> executeUnban(ctx, true)))
                        .then(Commands.literal("--case").executes(ctx -> executeUnban(ctx, true))))
                .build();
    }

    // ---- /mute, /unmute (TFM's /stfu - deliberately shadows Essentials) ---------------

    private LiteralCommandNode<CommandSourceStack> muteCommand() {
        return Commands.literal("mute")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/mute <player> [reason]"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> executeQuickMute(ctx, null))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeQuickMute(ctx, StringArgumentType.getString(ctx, "reason")))))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> unmuteCommand() {
        return Commands.literal("unmute")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/unmute <player>"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS).executes(this::executeUnmute))
                .build();
    }

    /**
     * {@code /mute} - TFM's {@code /stfu} (aliased {@code mute} there too): a quick,
     * always-public, fixed 5-minute toggle-mute for an online player, distinct from
     * {@code /punish mute}'s indefinite, staff-facing, explicitly-unmuted-later punishment
     * - both share the same underlying {@link MuteService}/{@code rigel_mutes} table, this
     * one just always sets a 5-minute expiry and always broadcasts. Toggles: running it
     * again on an already-muted target unmutes them early. Can't target another
     * Moderator+.
     */
    private int executeQuickMute(CommandContext<CommandSourceStack> ctx, @Nullable String reason) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sendPlayerNotFound(sender, targetName);
            return 0;
        }
        UUID targetUuid = target.getUniqueId();
        if (permissionGate.hasAtLeastCached(targetUuid, "moderator")) {
            sender.sendMessage(Component.text("This command cannot be used on other staff.", NamedTextColor.RED));
            return 0;
        }
        UUID actorUuid = actorUuid(sender);

        dbExecutor.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                if (muteService.isMuted(targetUuid, now)) {
                    muteService.unmute(targetUuid);
                    sync(() -> {
                        broadcastPublic("mute.unmute-public", "<red>{target} has been unmuted.",
                                "target", targetName);
                        target.sendMessage(plugin.messages().get("mute.unmute-target", "<green>You have been unmuted."));
                    });
                    return;
                }

                String effectiveReason = reason != null ? reason : "Muted by " + sender.getName();
                muteService.mute(targetUuid, actorUuid, effectiveReason, now, now + FIVE_MINUTES_MILLIS);
                String reasonSuffix = reason != null ? " Reason: " + reason : "";
                sync(() -> {
                    broadcastPublic("mute.public", "<red>{target} has been muted for 5 minutes.{reason}",
                            "target", targetName, "reason", reasonSuffix);
                    target.sendMessage(plugin.messages().get(
                            "mute.target", "<red>You have been muted for 5 minutes.{reason}",
                            "reason", reasonSuffix));
                });
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/mute", e);
            }
        });
        return 1;
    }

    /**
     * Builds a message from {@code messages.yml} (falling back to {@code fallback} if the
     * key isn't customized there) and shows it to every online player - RigelMCMod's
     * public admin-action broadcast convention, matching TFM's own (its ban/tban/permban/
     * mute/freeze/cage/smite commands are all publicly visible by default, not
     * staff-only). Console senders (never in {@code Bukkit.getOnlinePlayers()}) get it too.
     */
    private void broadcastPublic(String key, String fallback, String... placeholders) {
        Component component = plugin.messages().get(key, fallback, placeholders);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(component);
        }
        plugin.getLogger().info(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component));
    }

    private int executeMute(CommandContext<CommandSourceStack> ctx, String reasonAndFlags) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        UUID actorUuid = actorUuid(sender);
        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        CommandFlags flags = CommandFlags.parse(reasonAndFlags);
        String reason = flags.remainder().isBlank() ? "Muted by " + sender.getName() : flags.remainder();
        boolean silent = flags.has("s", "silent");

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> uuidOpt = resolveUuid(targetName, onlineTarget);
                if (uuidOpt.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                muteService.mute(uuidOpt.get(), actorUuid, reason, System.currentTimeMillis(), null);
                sync(() -> announcePunishment(sender, silent, "mute.indefinite-public",
                        "<gold>{target} was muted. Reason: {reason}", "target", targetName, "reason", reason));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/punish mute", e);
            }
        });
        return 1;
    }

    private int executeUnmute(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        Player onlineTarget = Bukkit.getPlayerExact(targetName);

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> uuidOpt = resolveUuid(targetName, onlineTarget);
                if (uuidOpt.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                boolean unmuted = muteService.unmute(uuidOpt.get());
                sync(() -> {
                    if (unmuted) {
                        broadcastPublic("mute.unmute-public", "<red>{target} has been unmuted.",
                                "target", targetName);
                        if (onlineTarget != null) {
                            onlineTarget.sendMessage(
                                    plugin.messages().get("mute.unmute-target", "<green>You have been unmuted."));
                        }
                    } else {
                        sender.sendMessage(
                                Component.text(targetName + " is not currently muted.", NamedTextColor.YELLOW));
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/punish unmute", e);
            }
        });
        return 1;
    }

    private int executeUnban(CommandContext<CommandSourceStack> ctx, boolean wholeCase) {
        CommandSender sender = ctx.getSource().getSender();
        String target = StringArgumentType.getString(ctx, "target");
        UUID actorUuid = actorUuid(sender);
        boolean isIp = IpAddressUtil.looksLikeIp(target);
        String ipHash = isIp ? ipHasher.hash(target) : null;

        dbExecutor.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                int revoked = banService.unban(target, ipHash, wholeCase, actorUuid, now);
                sync(() -> {
                    if (revoked == 0) {
                        sender.sendMessage(Component.text("'" + target + "' is not currently banned.", NamedTextColor.YELLOW));
                    } else {
                        sender.sendMessage(Component.text(
                                "Lifted " + revoked + " ban entry(ies) for '" + target + "'.", NamedTextColor.GREEN));
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/punish unban", e);
            }
        });
        return 1;
    }

    // ---- shared helpers ----------------------------------------------------------------

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }

    private static UUID actorUuid(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private Optional<UUID> resolveUuid(String name, Player online) throws SQLException {
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        return playerDao.findByLastKnownName(name).map(PlayerRecord::uuid);
    }

    /**
     * @return the IP hash to ban alongside the name for {@code /ban}/{@code /gtfo} (see
     *     {@code BanService#banByNameAndIp}'s own javadoc) - the target's current
     *     connection IP if they're online right now, else their most-recently-seen one
     *     from {@link #ipHistoryDao} ({@code findIpsForUuid} is already ordered
     *     most-recent-first). {@code null} if neither source has anything (e.g. banning a
     *     name that has never actually connected) - callers must handle that as "name-only
     *     ban", not an error.
     */
    @Nullable
    private String resolveIpHashForBan(UUID uuid, @Nullable Player online) throws SQLException {
        if (online != null && online.getAddress() != null) {
            return ipHasher.hash(online.getAddress().getAddress().getHostAddress());
        }
        return ipHistoryDao.findIpsForUuid(uuid).stream().findFirst().orElse(null);
    }

    private void sendPlayerNotFound(CommandSender sender, String name) {
        sender.sendMessage(Component.text("No player found matching '" + name + "'.", NamedTextColor.RED));
    }

    // ---- Fake-staff-target protection -------------------------------------------------
    //
    // RigelMCMod-original - TFM has no real equivalent to this on /ban/tempban/permban
    // (confirmed by reading its source directly: only its unrelated /warn blocks
    // targeting another admin at all). Applies to /ban, /tban, /permban, and /kick: if
    // the target currently holds Moderator+ rank, the action is blocked unless the
    // sender's rank strictly outranks the target's AND the sender passes --force-staff -
    // defends against a compromised or mistaken same-or-lower-rank staff action, while
    // still letting genuine escalation through (a Senior Admin disciplining a Moderator).
    // Console always bypasses - checked separately by each call site via `actorUuid ==
    // null`, since console is trusted root and has no rank of its own to compare.

    private boolean isBlockedByStaffProtection(@NotNull Rank senderRank, @NotNull Rank targetRank, boolean forceStaff) {
        Rank moderator = rankService.rank("moderator").orElseThrow();
        if (targetRank.weight() < moderator.weight()) {
            return false; // target doesn't currently hold staff rank - nothing to protect
        }
        boolean senderOutranks = senderRank.weight() > targetRank.weight();
        return !(senderOutranks && forceStaff);
    }

    /**
     * Non-blocking, cache-only rank lookup for a currently-<b>online</b> player -
     * suitable for {@code /kick}, whose target (and almost always its sender) are already
     * guaranteed online, so this avoids the DB round-trip {@link RankService#rankOf}
     * needs. Not accurate for an offline player - callers with a possibly-offline target
     * (ban/tban/permban) use {@link RankService#rankOf} instead, from within their
     * existing {@link #dbExecutor} dispatch.
     */
    private Rank onlineApproxRank(UUID uuid) {
        for (Rank rank : permissionGate.laddersDescending()) {
            if (permissionGate.hasAtLeastCached(uuid, rank.id())) {
                return rank;
            }
        }
        return rankService.rank("default").orElseThrow();
    }

    private void sendStaffProtectionDenied(CommandSender sender, String targetName) {
        sender.sendMessage(Component.text(
                targetName + " currently holds staff rank - this action requires you to strictly outrank"
                        + " them AND pass --force-staff to proceed (protects against a compromised or"
                        + " mistaken same-or-lower-rank staff action).",
                NamedTextColor.RED));
    }

    /**
     * Resolves the {@code argumentName} argument to an online {@link Player}, sending a
     * "not found" message and returning {@code null} if it isn't one - freeze and smite
     * both only make sense against someone currently connected, unlike the ban commands
     * which also support offline targets via the database.
     */
    @Nullable
    private Player onlineTargetOrFail(CommandContext<CommandSourceStack> ctx, String argumentName) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, argumentName);
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sendPlayerNotFound(sender, name);
        }
        return target;
    }

    /**
     * Confirms to the sender, and (unless {@code -s}/{@code --silent}) publicly announces
     * the action to every online player - matching TFM's own {@code adminAction} broadcast
     * convention (its ban/tban/permban/mute commands are all publicly visible by default,
     * not staff-only), not just a staff-facing notice.
     */
    private void announcePunishment(
            CommandSender sender, boolean silent, String key, String fallback, String... placeholders) {
        if (silent) {
            sender.sendMessage(plugin.messages().get(key, fallback, placeholders));
            return;
        }
        broadcastPublic(key, fallback, placeholders);
    }

    private void logAndNotifyFailure(CommandSender sender, String commandName, SQLException e) {
        plugin.getLogger().log(Level.WARNING, "Database error handling " + commandName, e);
        sync(() -> sender.sendMessage(Component.text("An internal error occurred. Check the console.", NamedTextColor.RED)));
    }

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
