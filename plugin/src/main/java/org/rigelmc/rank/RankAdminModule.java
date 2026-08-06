package org.rigelmc.rank;

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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.chat.PlayerDisplayService;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.command.PlayerSuggestions;
import org.rigelmc.core.PluginModule;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;

/**
 * {@code /adminconfig} - console-only server admin configuration, TFM-style. The
 * console-only restriction is a deliberate security boundary: granting the top rank
 * should require actual server access (console or RCON), not just being in-game -
 * otherwise rank management could become purely an in-game privilege-escalation
 * problem. See docs/architecture.md's rank/title design.
 *
 * <ul>
 *   <li>{@code /adminconfig add <player>} - promotes a player to the baseline staff rank
 *       (Moderator) - deliberately takes no rank argument, unlike {@code setrank}: adding
 *       someone as staff and granting them a specific higher rank are kept as two separate,
 *       explicit steps rather than one command that could silently hand out any rank.</li>
 *   <li>{@code /adminconfig remove <player>} - demotes a player back to the default
 *       (unranked) rank.</li>
 *   <li>{@code /adminconfig setrank <player> <rank>} - sets any specific rank directly.</li>
 *   <li>{@code /adminconfig list} - lists every player currently holding a non-default rank,
 *       highest rank first. The same data backs the web panel's admin list.</li>
 *   <li>{@code /adminconfig reset} - fully resets the admin list (every player back to the
 *       default rank) in one bulk operation. Destructive and irreversible, so bare {@code
 *       reset} only prints a warning; the actual reset requires the explicit follow-up
 *       {@code /adminconfig reset confirm} - a deliberate two-step confirmation so a typo
 *       or muscle-memory keystroke can't wipe every staff rank on the server at once.</li>
 * </ul>
 */
public final class RankAdminModule implements PluginModule {

    private final RankService rankService;
    private final TitleService titleService;
    private final PlayerDao playerDao;
    private final PermissionGate permissionGate;
    private final PrefixService prefixService;
    private final PlayerDisplayService displayService;
    private final NameTagService nameTagService;
    private final VaultChatBridge vaultChatBridge;
    private final AuditLogService auditLogService;
    private final ExecutorService dbExecutor;
    private RigelMCMod plugin;

    public RankAdminModule(
            @NotNull RankService rankService,
            @NotNull TitleService titleService,
            @NotNull PlayerDao playerDao,
            @NotNull PermissionGate permissionGate,
            @NotNull PrefixService prefixService,
            @NotNull PlayerDisplayService displayService,
            @NotNull NameTagService nameTagService,
            @NotNull VaultChatBridge vaultChatBridge,
            @NotNull AuditLogService auditLogService,
            @NotNull ExecutorService dbExecutor) {
        this.rankService = rankService;
        this.titleService = titleService;
        this.playerDao = playerDao;
        this.permissionGate = permissionGate;
        this.prefixService = prefixService;
        this.displayService = displayService;
        this.nameTagService = nameTagService;
        this.vaultChatBridge = vaultChatBridge;
        this.auditLogService = auditLogService;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String id() {
        return "rank";
    }

    @Override
    public boolean isEnabled(org.rigelmc.core.RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(
                plugin, this::reconcilePermissionCache,
                PERMISSION_SELF_HEAL_INTERVAL_TICKS, PERMISSION_SELF_HEAL_INTERVAL_TICKS);
    }

    /**
     * Also runs one immediate reconciliation pass on {@code /rmcm reload}, rather than
     * leaving an admin to wait out the periodic interval - matches the real workflow a
     * user actually reached for when this cache went stale (they tried {@code /rmcm
     * reload} first, before the console-only {@code /adminconfig setrank} workaround); see
     * {@link #reconcilePermissionCache}'s own javadoc for why the cache can go stale in
     * the first place.
     */
    @Override
    public void onConfigReload(org.rigelmc.core.RigelConfig config) {
        reconcilePermissionCache();
    }

    /** How often {@link #reconcilePermissionCache} re-checks every online player - 20 ticks/sec x 60. */
    private static final long PERMISSION_SELF_HEAL_INTERVAL_TICKS = 1200L;

    /**
     * Self-heal for {@link PermissionGate}'s online-rank cache - defense-in-depth against
     * the join-time population ({@code core.PlayerLoginListener#onJoin}'s async DB
     * round-trip, then a hop back to the main thread to call {@link
     * PermissionGate#applyRank}) ever silently failing partway through for a given player,
     * leaving their cache entry missing or stale for the rest of their session with no
     * other trigger to fix it. Runs periodically (see {@link #registerListeners}) and also
     * immediately on {@code /rmcm reload} (see {@link #onConfigReload}).
     *
     * <p>Real, user-reported symptom this closes: an admin's rank-gated commands (e.g.
     * {@code /ban}, {@code /adminworld}) went completely invisible after a restart - "Unknown
     * or incomplete command," since a failed Brigadier {@code .requires()} hides the whole
     * command node rather than denying with a message - and the only known fix was a
     * console-run {@code /adminconfig setrank} re-populating that exact same cache manually
     * (see {@link #executeSetRank}'s own {@code permissionGate.applyRank} call). This makes
     * that self-correct automatically instead of needing an admin to notice, diagnose, and
     * manually intervene every time.</p>
     *
     * <p>Cheap in the overwhelmingly common case: only re-applies (which clears and re-
     * grants every permission node, so isn't free) when the freshly-fetched DB rank
     * actually disagrees with - or is entirely missing from - the cache; otherwise this is
     * one DB read per online player per call and nothing else.</p>
     */
    private void reconcilePermissionCache() {
        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            return;
        }
        dbExecutor.submit(() -> {
            for (Player player : online) {
                UUID uuid = player.getUniqueId();
                try {
                    String actualRankId = rankService.rankOf(uuid).id();
                    if (actualRankId.equals(permissionGate.cachedRankId(uuid))) {
                        continue; // already correct - the common case
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player stillOnline = Bukkit.getPlayer(uuid);
                        if (stillOnline != null) {
                            permissionGate.applyRank(stillOnline, actualRankId);
                            stillOnline.updateCommands();
                        }
                    });
                } catch (SQLException e) {
                    plugin.getLogger().log(
                            Level.WARNING, "Permission cache self-heal failed for " + player.getName(), e);
                }
            }
        });
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(adminconfigCommand(), "Console-only server admin configuration (rank management)");
    }

    private LiteralCommandNode<CommandSourceStack> adminconfigCommand() {
        // Deliberately no top-level .requires() gate here: Brigadier makes a command
        // whose requirement fails invisible to that sender entirely, which surfaces as
        // Minecraft's generic "Unknown or incomplete command" - genuinely confusing for
        // a player who doesn't know this command is console-only (confirmed by a real
        // user hitting exactly this in testing). The console-only check instead happens
        // inside rejectIfNotConsole, called first by every fallback/execute path below
        // (including the usage-help ones) - a player gets a clear "console only" message
        // at every depth, never usage text, and never a mysterious parse error. This is
        // deliberately airtight: adding usage-help fallbacks here must never accidentally
        // let a player see anything beyond that rejection message.
        return Commands.literal("adminconfig")
                .executes(ctx -> showUsageOrReject(
                        ctx, "/adminconfig <add|remove|setrank|list> [player] [rank]"))
                .then(Commands.literal("add")
                        // No optional rank argument here, deliberately - see the class
                        // javadoc: promoting someone to Moderator and granting a *specific*
                        // higher rank are kept as two separate, explicit steps.
                        .executes(ctx -> showUsageOrReject(ctx, "/adminconfig add <player>"))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(ctx -> executeSetRank(ctx, Rank.MODERATOR.id()))))
                .then(Commands.literal("remove")
                        .executes(ctx -> showUsageOrReject(ctx, "/adminconfig remove <player>"))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(ctx -> executeSetRank(ctx, Rank.DEFAULT.id()))))
                .then(Commands.literal("setrank")
                        .executes(ctx -> showUsageOrReject(ctx, "/adminconfig setrank <player> <rank>"))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(ctx -> showUsageOrReject(ctx, "/adminconfig setrank <player> <rank>"))
                                .then(Commands.argument("rank", StringArgumentType.word())
                                        .executes(ctx ->
                                                executeSetRank(ctx, StringArgumentType.getString(ctx, "rank"))))))
                .then(Commands.literal("list").executes(this::executeList))
                .then(Commands.literal("reset")
                        .executes(this::executeResetWarning)
                        .then(Commands.literal("confirm").executes(this::executeResetConfirmed)))
                .build();
    }

    /** @return {@code true} if the sender was rejected (and already notified); {@code false} if it's console/RCON */
    private boolean rejectIfNotConsole(CommandSender sender) {
        if (sender instanceof Player) {
            sender.sendMessage(Component.text(
                    "/adminconfig can only be run from the server console or RCON, not in-game.",
                    NamedTextColor.RED));
            return true;
        }
        return false;
    }

    private int showUsageOrReject(CommandContext<CommandSourceStack> ctx, String usage) {
        CommandSender sender = ctx.getSource().getSender();
        if (rejectIfNotConsole(sender)) {
            return 0;
        }
        return CommandUsage.show(sender, usage);
    }

    private int executeSetRank(CommandContext<CommandSourceStack> ctx, String rankId) {
        CommandSender sender = ctx.getSource().getSender();
        if (rejectIfNotConsole(sender)) {
            return 0;
        }
        String targetName = StringArgumentType.getString(ctx, "player");
        Player online = Bukkit.getPlayerExact(targetName);

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> uuidOpt = online != null
                        ? Optional.of(online.getUniqueId())
                        : playerDao.findByLastKnownName(targetName).map(PlayerRecord::uuid);
                if (uuidOpt.isEmpty()) {
                    sync(() -> sender.sendMessage(Component.text(
                            "No player found matching '" + targetName + "' - they must have joined at least"
                                    + " once, or be online now.",
                            NamedTextColor.RED)));
                    return;
                }

                // Warn (don't block) when the offline-name resolution above was ambiguous -
                // see PlayerDao#findByLastKnownName's javadoc for why more than one account
                // can share a name, and why this still proceeds against whichever matched
                // was seen most recently rather than refusing outright.
                if (online == null) {
                    int matches = playerDao.countByLastKnownName(targetName);
                    if (matches > 1) {
                        plugin.getLogger().warning(
                                matches + " different accounts have used the name '" + targetName
                                        + "' - /adminconfig resolved to whichever was seen most recently"
                                        + " (uuid " + uuidOpt.get() + "). If that's the wrong account, have the"
                                        + " intended player join first so their session becomes the"
                                        + " most-recently-seen match, then re-run this command.");
                    }
                }

                UUID uuid = uuidOpt.get();
                String previous;
                try {
                    previous = rankService.setRank(uuid, rankId);
                } catch (IllegalArgumentException e) {
                    sync(() -> sender.sendMessage(Component.text(
                            "Unknown rank '" + rankId + "'. Valid ranks: default, moderator, admin, senior_admin.",
                            NamedTextColor.RED)));
                    return;
                }

                auditLogService.record(null, "SETRANK", uuid, "via adminconfig: " + previous + " -> " + rankId);
                Rank newRank = null;
                var titleIds = online != null ? titleService.titleIdsFor(uuid) : null;
                if (online != null) {
                    prefixService.refresh(uuid, plugin.rigelConfig());
                    newRank = rankService.rankOf(uuid);
                }
                Rank finalNewRank = newRank;

                sync(() -> {
                    sender.sendMessage(Component.text(
                            "Set " + targetName + "'s rank to " + rankId + " (was " + previous + ").",
                            NamedTextColor.GREEN));
                    if (online != null) {
                        permissionGate.applyRank(online, rankId);
                        // Corrects the generic [OP] bracket's visibility for the just-
                        // refreshed prefix cache - see PrefixService's javadoc for why
                        // refresh() alone can't resolve this (no live isOp() there).
                        prefixService.applyOpStatus(uuid, online.isOp());
                        displayService.apply(online);
                        nameTagService.applyTeam(online, finalNewRank, titleIds);
                        vaultChatBridge.setPrefix(online,
                                LegacyComponentSerializer.legacySection().serialize(prefixService.prefixFor(uuid)));
                        // Re-syncs this client's cached Brigadier command tree so rank-gated
                        // commands' tab-completion reflects the new rank immediately, instead
                        // of staying stuck with whatever was visible at their last tree sync
                        // (normally login) until they relog - see PlayerLoginListener's
                        // matching call for the fuller rationale (same underlying bug).
                        online.updateCommands();
                        online.sendMessage(
                                Component.text("Your rank has been updated to " + rankId + ".", NamedTextColor.GOLD));
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to process /adminconfig", e);
                sync(() -> sender.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (rejectIfNotConsole(sender)) {
            return 0;
        }
        dbExecutor.submit(() -> {
            try {
                List<PlayerRecord> ranked = playerDao.findAllRanked();
                sync(() -> sender.sendMessage(formatRankedList(ranked)));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to list ranked admins via /adminconfig list", e);
                sync(() -> sender.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    /** {@code /adminconfig reset} (bare) - warns only, performs no reset. See {@link #executeResetConfirmed}. */
    private int executeResetWarning(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (rejectIfNotConsole(sender)) {
            return 0;
        }
        sender.sendMessage(Component.text(
                "This will reset EVERY player's rank back to default, removing all staff at once."
                        + " This cannot be undone. Run '/adminconfig reset confirm' to proceed.",
                NamedTextColor.RED));
        return 0;
    }

    /**
     * {@code /adminconfig reset confirm} - the actual destructive reset. Snapshots who was
     * ranked before resetting (so online players' live permissions/prefix/nametag can be
     * refreshed to match afterward), bulk-updates every row via {@link
     * PlayerDao#resetAllRanks()}, then re-syncs each currently-online affected player -
     * mirroring {@link #executeSetRank}'s single-player refresh, just for many players at
     * once. DB/service calls stay off the main thread; only the final Bukkit API mutations
     * run inside {@link #sync}.
     */
    private int executeResetConfirmed(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (rejectIfNotConsole(sender)) {
            return 0;
        }
        dbExecutor.submit(() -> {
            try {
                List<PlayerRecord> previouslyRanked = playerDao.findAllRanked();
                int count = playerDao.resetAllRanks();
                auditLogService.record(
                        null, "ADMINCONFIG_RESET", null, "reset " + count + " player rank(s) back to default");

                List<Runnable> liveUpdates = new java.util.ArrayList<>();
                for (PlayerRecord record : previouslyRanked) {
                    Player online = Bukkit.getPlayer(record.uuid());
                    if (online == null) {
                        continue;
                    }
                    UUID uuid = record.uuid();
                    prefixService.refresh(uuid, plugin.rigelConfig());
                    // See PrefixService's javadoc - refresh() alone can't resolve the
                    // generic [OP] bracket's visibility, this does (matches the existing
                    // Bukkit.getPlayer(...) read a few lines above already tolerating a
                    // cheap Player read from this async thread).
                    prefixService.applyOpStatus(uuid, online.isOp());
                    Rank newRank = rankService.rankOf(uuid);
                    var titleIds = titleService.titleIdsFor(uuid);
                    String legacyPrefix =
                            LegacyComponentSerializer.legacySection().serialize(prefixService.prefixFor(uuid));
                    liveUpdates.add(() -> {
                        permissionGate.applyRank(online, Rank.DEFAULT.id());
                        displayService.apply(online);
                        nameTagService.applyTeam(online, newRank, titleIds);
                        vaultChatBridge.setPrefix(online, legacyPrefix);
                        online.updateCommands();
                        online.sendMessage(
                                Component.text("Your rank has been reset to default.", NamedTextColor.GOLD));
                    });
                }

                sync(() -> {
                    sender.sendMessage(Component.text(
                            "Reset " + count + " player(s) back to the default rank.", NamedTextColor.GREEN));
                    for (Runnable update : liveUpdates) {
                        update.run();
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to process /adminconfig reset", e);
                sync(() -> sender.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    @NotNull
    private Component formatRankedList(@NotNull List<PlayerRecord> ranked) {
        if (ranked.isEmpty()) {
            return Component.text("No players currently hold a non-default rank.", NamedTextColor.GRAY);
        }
        Component message = Component.text(ranked.size() + " ranked admin(s):", NamedTextColor.GOLD);
        for (PlayerRecord record : ranked) {
            String rankLabel = rankService.rank(record.rankId())
                    .map(Rank::displayName)
                    .orElse(record.rankId());
            message = message.appendNewline()
                    .append(Component.text("  " + record.lastKnownName() + " ", NamedTextColor.WHITE))
                    .append(Component.text("(" + rankLabel + ")", NamedTextColor.AQUA));
        }
        return message;
    }

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
