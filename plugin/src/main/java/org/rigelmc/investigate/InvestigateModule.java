package org.rigelmc.investigate;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.command.PlayerSuggestions;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.dao.IpHistoryDao;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;
import org.rigelmc.identity.IpHasher;
import org.rigelmc.protect.CommandAccessRegistry;
import org.rigelmc.protect.CommandAccessRule;
import org.rigelmc.rank.PermissionGate;

/**
 * Admin investigative/QoL tools - TFM ref: {@code Command_cmdspy}, {@code
 * Command_signspy}, {@code Command_potionspy}, {@code Command_whohas}, {@code
 * Command_findip}, {@code Command_radar}, {@code Command_gcmd}, each studied directly.
 * {@code /bookspy} has no TFM equivalent - added to close the same real gap a sign is
 * already covered for (see {@code SpyListener}'s javadoc). All Moderator+ except {@code
 * /radar} (TFM itself leaves that open to everyone) and {@code /gcmd} (Senior Admin,
 * matching TFM's own {@code Rank.SUPER_ADMIN} gate - see that command's own javadoc for
 * why it needs a materially higher bar than the rest of this module).
 *
 * <p>{@code /findip} necessarily differs from TFM's own: RigelMCMod never stores
 * plaintext IPs (salted HMAC-SHA256 hashes only, see {@code identity.IpHasher}), so this
 * displays hashes, not addresses. Still useful for correlating alt accounts sharing an IP
 * (two players with a matching hash share a real IP) - just not for reading the address
 * itself.</p>
 */
public final class InvestigateModule implements PluginModule {

    private final PermissionGate permissionGate;
    private final SpyService spyService;
    private final PlayerDao playerDao;
    private final IpHistoryDao ipHistoryDao;
    private final IpHasher ipHasher;
    private final ExecutorService dbExecutor;
    private final CommandAccessRegistry commandAccessRegistry;
    private final AuditLogService auditLogService;
    private RigelMCMod plugin;

    public InvestigateModule(
            @NotNull PermissionGate permissionGate,
            @NotNull SpyService spyService,
            @NotNull PlayerDao playerDao,
            @NotNull IpHistoryDao ipHistoryDao,
            @NotNull IpHasher ipHasher,
            @NotNull ExecutorService dbExecutor,
            @NotNull CommandAccessRegistry commandAccessRegistry,
            @NotNull AuditLogService auditLogService) {
        this.permissionGate = permissionGate;
        this.spyService = spyService;
        this.playerDao = playerDao;
        this.ipHistoryDao = ipHistoryDao;
        this.ipHasher = ipHasher;
        this.dbExecutor = dbExecutor;
        this.commandAccessRegistry = commandAccessRegistry;
        this.auditLogService = auditLogService;
    }

    @Override
    public String id() {
        return "investigate";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(new SpyListener(spyService), plugin);
        plugin.getServer().getPluginManager().registerEvents(new InvestigateQuitListener(spyService), plugin);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(cmdSpyCommand(), "Toggle relaying other players' commands to you - Moderator+",
                List.of("commandspy", "cspy"));
        registrar.register(signSpyCommand(), "Toggle relaying sign edits to you - Moderator+", List.of("sspy"));
        registrar.register(potionSpyCommand(), "Toggle relaying thrown potions to you - Moderator+",
                List.of("potspy"));
        registrar.register(bookSpyCommand(), "Toggle relaying written book contents to you - Moderator+",
                List.of("bspy"));
        registrar.register(whoHasCommand(), "Find (or clear) online players holding a specific item - Moderator+");
        registrar.register(findIpCommand(), "Look up a player's known IP hashes - Moderator+", List.of("ips", "ip"));
        registrar.register(radarCommand(), "List nearby online players by distance");
        registrar.register(gCmdCommand(), "Send a command as another online player - Senior Admin");
    }

    // ---- /cmdspy [all|off] --------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> cmdSpyCommand() {
        return Commands.literal("cmdspy")
                .requires(source -> hasRank(source, "moderator") && source.getSender() instanceof Player)
                .executes(ctx -> executeSetCommandSpy(ctx, !spyService.isCommandSpy(senderUuid(ctx))))
                .then(Commands.literal("all").executes(ctx -> executeSetCommandSpy(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> executeSetCommandSpy(ctx, false)))
                .build();
    }

    private int executeSetCommandSpy(CommandContext<CommandSourceStack> ctx, boolean on) {
        UUID uuid = senderUuid(ctx);
        spyService.setCommandSpy(uuid, on);
        ctx.getSource()
                .getSender()
                .sendMessage(Component.text(
                        on ? "Command spy enabled - other players' commands will be relayed to you."
                                : "Command spy disabled.",
                        NamedTextColor.AQUA));
        return 1;
    }

    // ---- /signspy -------------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> signSpyCommand() {
        return Commands.literal("signspy")
                .requires(source -> hasRank(source, "moderator") && source.getSender() instanceof Player)
                .executes(ctx -> {
                    boolean on = spyService.toggleSignSpy(senderUuid(ctx));
                    ctx.getSource()
                            .getSender()
                            .sendMessage(Component.text(
                                    on ? "Sign spy enabled - sign edits will be relayed to you."
                                            : "Sign spy disabled.",
                                    NamedTextColor.AQUA));
                    return 1;
                })
                .build();
    }

    // ---- /potionspy -----------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> potionSpyCommand() {
        return Commands.literal("potionspy")
                .requires(source -> hasRank(source, "moderator") && source.getSender() instanceof Player)
                .executes(ctx -> {
                    boolean on = spyService.togglePotionSpy(senderUuid(ctx));
                    ctx.getSource()
                            .getSender()
                            .sendMessage(Component.text(
                                    on ? "Potion spy enabled - thrown potions will be relayed to you."
                                            : "Potion spy disabled.",
                                    NamedTextColor.AQUA));
                    return 1;
                })
                .build();
    }

    // ---- /bookspy ---------------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> bookSpyCommand() {
        return Commands.literal("bookspy")
                .requires(source -> hasRank(source, "moderator") && source.getSender() instanceof Player)
                .executes(ctx -> {
                    boolean on = spyService.toggleBookSpy(senderUuid(ctx));
                    ctx.getSource()
                            .getSender()
                            .sendMessage(Component.text(
                                    on ? "Book spy enabled - written book contents will be relayed to you."
                                            : "Book spy disabled.",
                                    NamedTextColor.AQUA));
                    return 1;
                })
                .build();
    }

    // ---- /whohas <item> [-clear] ---------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> whoHasCommand() {
        return Commands.literal("whohas")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/whohas <item> [-clear]"))
                .then(Commands.argument("item", StringArgumentType.word())
                        .executes(ctx -> executeWhoHas(ctx, false))
                        .then(Commands.literal("-clear").executes(ctx -> executeWhoHas(ctx, true))))
                .build();
    }

    private int executeWhoHas(CommandContext<CommandSourceStack> ctx, boolean clear) {
        CommandSender sender = ctx.getSource().getSender();
        String itemInput = StringArgumentType.getString(ctx, "item");
        Material material = Material.matchMaterial(itemInput);
        if (material == null || !material.isItem()) {
            sender.sendMessage(Component.text("Unknown item material '" + itemInput + "'.", NamedTextColor.RED));
            return 0;
        }

        List<String> matches = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            PlayerInventory inventory = online.getInventory();
            if (!inventory.contains(material)) {
                continue;
            }
            matches.add(online.getName());
            if (clear) {
                inventory.remove(material);
            }
        }

        if (matches.isEmpty()) {
            sender.sendMessage(Component.text("No online player is holding " + itemInput + ".", NamedTextColor.GRAY));
            return 1;
        }
        String verb = clear ? "Cleared " + itemInput + " from: " : "Holding " + itemInput + ": ";
        sender.sendMessage(Component.text(verb + String.join(", ", matches), NamedTextColor.AQUA));
        return 1;
    }

    // ---- /findip <player> -----------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> findIpCommand() {
        return Commands.literal("findip")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/findip <player>"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(this::executeFindIp))
                .build();
    }

    private int executeFindIp(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        Player online = Bukkit.getPlayerExact(targetName);

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> uuidOpt = online != null
                        ? Optional.of(online.getUniqueId())
                        : playerDao.findByLastKnownName(targetName).map(PlayerRecord::uuid);
                if (uuidOpt.isEmpty()) {
                    sync(() -> sender.sendMessage(Component.text(
                            "No player found matching '" + targetName + "'.", NamedTextColor.RED)));
                    return;
                }
                Set<String> hashes = ipHistoryDao.findIpsForUuid(uuidOpt.get());
                sync(() -> {
                    if (hashes.isEmpty()) {
                        sender.sendMessage(Component.text(
                                targetName + " has no recorded IP history.", NamedTextColor.GRAY));
                        return;
                    }
                    sender.sendMessage(Component.text(
                            targetName + "'s known IP hashes (" + hashes.size() + ") - RigelMCMod never stores"
                                    + " plaintext IPs, these are one-way salted hashes, useful only for spotting a"
                                    + " shared IP between two accounts, not for reading an address:",
                            NamedTextColor.AQUA));
                    for (String hash : hashes) {
                        sender.sendMessage(Component.text("  " + hash, NamedTextColor.GRAY));
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to process /findip", e);
                sync(() -> sender.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
            }
        });
        return 1;
    }

    // ---- /radar [radius] --------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> radarCommand() {
        return Commands.literal("radar")
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> executeRadar(ctx, plugin.rigelConfig().radarDefaultRadius()))
                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                        .executes(ctx -> executeRadar(
                                ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"))))
                .build();
    }

    private int executeRadar(CommandContext<CommandSourceStack> ctx, int requestedRadius) {
        Player player = (Player) ctx.getSource().getSender();
        int radius = Math.min(requestedRadius, plugin.rigelConfig().radarMaxRadius());
        Location origin = player.getLocation();

        List<Player> nearby = new ArrayList<>();
        for (Player other : player.getWorld().getPlayers()) {
            if (other.equals(player)) {
                continue;
            }
            if (other.getLocation().distance(origin) <= radius) {
                nearby.add(other);
            }
        }
        if (nearby.isEmpty()) {
            player.sendMessage(Component.text(
                    "No players within " + radius + " blocks.", NamedTextColor.GRAY));
            return 1;
        }
        nearby.sort(Comparator.comparingDouble(other -> other.getLocation().distance(origin)));

        player.sendMessage(Component.text("Nearby players:", NamedTextColor.AQUA));
        for (Player other : nearby) {
            int distance = (int) Math.round(other.getLocation().distance(origin));
            player.sendMessage(Component.text("  " + other.getName() + " - " + distance + " blocks", NamedTextColor.GRAY));
        }
        return 1;
    }

    // ---- /gcmd <player> <command...> -------------------------------------------------------

    /**
     * TFM ref: {@code Command_gcmd}, studied directly from its real source - "send a
     * command as someone else," gated to TFM's top rank ({@code Rank.SUPER_ADMIN}, here
     * {@code senior_admin}) for the same reason this project gates it that high too: it
     * dispatches an arbitrary command <i>as another player</i>, which is exactly why
     * TFM's own implementation never just calls {@code dispatchCommand} outright - it
     * re-validates the outgoing command against its own {@code CommandBlocker} first
     * ({@code if (plugin.cb.isCommandBlocked(outCommand, sender)) { return true; }}).
     *
     * <p><b>Why that re-check is load-bearing, not defensive boilerplate</b>: {@link
     * org.rigelmc.protect.BlockedCommandListener}'s entire enforcement runs off {@code
     * PlayerCommandPreprocessEvent} - an event that only fires for a real player pressing
     * enter on a typed command. It <b>never fires</b> for a command dispatched
     * programmatically via {@link Bukkit#dispatchCommand}. Without an explicit re-check
     * here, {@code /gcmd} would be a complete, silent bypass of every {@code
     * protect.command-access} rule in the file - including every {@code n:b:...}
     * "nobody" entry (e.g. {@code /stop}, {@code /restart}) - for anyone who can run
     * {@code /gcmd} at all. {@link CommandAccessRegistry#matchRawCommand} exists
     * specifically to close this off, matching TFM's real fix exactly.</p>
     *
     * <p>Checked against the <i>sender's</i> rank, not the target's - matching TFM's own
     * real behavior (verified from its source, not guessed). {@code /gcmd}'s whole
     * legitimate purpose is impersonating a lower-rank player (to test what they can/
     * can't do, or to force a specific benign action), so gating on the target's rank
     * instead would defeat that entirely; gating on the sender's still closes the actual
     * vulnerability, since the sender is already at the top of the ladder and the only
     * thing that can still block them is an unconditional "nobody" rule.</p>
     */
    private LiteralCommandNode<CommandSourceStack> gCmdCommand() {
        return Commands.literal("gcmd")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/gcmd <player> <command...>"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/gcmd <player> <command...>"))
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(this::executeGCmd)))
                .build();
    }

    private int executeGCmd(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        String outCommand = StringArgumentType.getString(ctx, "command");

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(Component.text(
                    "'" + targetName + "' must be online to use /gcmd on them.", NamedTextColor.RED));
            return 0;
        }

        Optional<CommandAccessRule> blockedBy = commandAccessRegistry.matchRawCommand(outCommand);
        if (blockedBy.isPresent()) {
            CommandAccessRule rule = blockedBy.get();
            boolean senderClears = rule.requiredRankId() != null && hasRank(ctx.getSource(), rule.requiredRankId());
            if (!senderClears) {
                sender.sendMessage(Component.text(
                        "That command is blocked by protect.command-access and cannot be forwarded via /gcmd.",
                        NamedTextColor.RED));
                return 0;
            }
        }

        UUID actorUuid = sender instanceof Player player ? player.getUniqueId() : null;
        UUID targetUuid = target.getUniqueId();
        dbExecutor.submit(() -> {
            try {
                auditLogService.record(actorUuid, "GCMD", targetUuid, outCommand);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to audit-log /gcmd", e);
            }
        });

        sender.sendMessage(Component.text(
                "Sending command as " + target.getName() + ": /" + outCommand, NamedTextColor.GOLD));
        boolean dispatched;
        try {
            dispatched = Bukkit.dispatchCommand(target, outCommand);
        } catch (RuntimeException e) {
            sender.sendMessage(Component.text("Error sending command: " + e.getMessage(), NamedTextColor.RED));
            return 0;
        }
        sender.sendMessage(dispatched
                ? Component.text("Command sent.", NamedTextColor.GREEN)
                : Component.text("Unknown error sending command.", NamedTextColor.RED));
        return 1;
    }

    // ---- shared helpers -------------------------------------------------------------------

    private static UUID senderUuid(CommandContext<CommandSourceStack> ctx) {
        return ((Player) ctx.getSource().getSender()).getUniqueId();
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

    /**
     * Not part of {@link SpyListener} - a separate tiny listener rather than growing that
     * class's own scope (it's about relaying, not about the toggle-state lifecycle).
     */
    private static final class InvestigateQuitListener implements org.bukkit.event.Listener {
        private final SpyService spyService;

        InvestigateQuitListener(SpyService spyService) {
            this.spyService = spyService;
        }

        @org.bukkit.event.EventHandler
        public void onQuit(org.bukkit.event.player.@NotNull PlayerQuitEvent event) {
            spyService.clear(event.getPlayer().getUniqueId());
        }
    }
}
