package org.rigelmc.guild;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.command.PlayerSuggestions;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;
import org.rigelmc.guild.plot.GuildPlotBypassService;
import org.rigelmc.guild.plot.GuildPlotWorldService;
import org.rigelmc.guild.plot.PlotCosmetic;
import org.rigelmc.guild.plot.PlotCosmeticApplier;
import org.rigelmc.guild.plot.PlotCosmeticService;
import org.rigelmc.guild.plot.PlotGridAllocator;
import org.rigelmc.rank.PermissionGate;

/**
 * The full {@code /guild} Brigadier tree - split out from {@link GuildModule} matching
 * {@code protect.area.ProtectAreaCommand}'s own "split purely for subcommand count"
 * precedent. Unlike that command (Moderator+ gated at the root), most of this tree is
 * self-service - only the {@code admin} subtree is staff-gated - so {@code .requires()} is
 * applied per subcommand rather than once at the root, matching {@code
 * world.WorldModule}'s existing idiom for the same "mixed self-service/staff" shape. {@code
 * admin plotbypass} is gated stricter still - Senior Admin, not just Moderator+ - since it
 * unlocks build/break anywhere in the shared plot world, not just moderation of one guild;
 * see {@code guild.plot.GuildPlotBoundaryGuard}.
 *
 * <p>{@code plot} subtree covers {@code tp} and {@code cosmetic list|buy|apply}.</p>
 */
public final class GuildCommand {

    private final GuildService guildService;
    private final GuildInviteManager inviteManager;
    private final GuildPlotWorldService guildPlotWorldService;
    private final PlotCosmeticService plotCosmeticService;
    private final PlayerDao playerDao;
    private final PermissionGate permissionGate;
    private final AuditLogService auditLogService;
    private final ExecutorService dbExecutor;
    private final GuildPlotBypassService guildPlotBypassService;
    private RigelMCMod plugin;

    public GuildCommand(
            @NotNull GuildService guildService,
            @NotNull GuildInviteManager inviteManager,
            @NotNull GuildPlotWorldService guildPlotWorldService,
            @NotNull PlotCosmeticService plotCosmeticService,
            @NotNull PlayerDao playerDao,
            @NotNull PermissionGate permissionGate,
            @NotNull AuditLogService auditLogService,
            @NotNull ExecutorService dbExecutor,
            @NotNull GuildPlotBypassService guildPlotBypassService) {
        this.guildService = guildService;
        this.inviteManager = inviteManager;
        this.guildPlotWorldService = guildPlotWorldService;
        this.plotCosmeticService = plotCosmeticService;
        this.playerDao = playerDao;
        this.permissionGate = permissionGate;
        this.auditLogService = auditLogService;
        this.dbExecutor = dbExecutor;
        this.guildPlotBypassService = guildPlotBypassService;
    }

    void bind(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @NotNull
    LiteralCommandNode<CommandSourceStack> build(@NotNull String rootLiteral) {
        return Commands.literal(rootLiteral)
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(),
                        "/" + rootLiteral + " <create|invite|accept|deny|kick|promote|demote|leave|disband|"
                                + "transferowner|info|list>"))
                .then(Commands.literal("create")
                        .requires(this::isPlayer)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild create <name>"))
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::executeCreate)))
                .then(Commands.literal("invite")
                        .requires(this::isPlayer)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild invite <player>"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(this::executeInvite)))
                .then(Commands.literal("accept").requires(this::isPlayer).executes(this::executeAccept))
                .then(Commands.literal("deny").requires(this::isPlayer).executes(this::executeDeny))
                .then(Commands.literal("kick")
                        .requires(this::isPlayer)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild kick <player>"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(this::executeKick)))
                .then(Commands.literal("promote")
                        .requires(this::isPlayer)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild promote <player>"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(ctx -> executeChangeRole(ctx, true))))
                .then(Commands.literal("demote")
                        .requires(this::isPlayer)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild demote <player>"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(ctx -> executeChangeRole(ctx, false))))
                .then(Commands.literal("leave").requires(this::isPlayer).executes(this::executeLeave))
                .then(Commands.literal("disband")
                        .requires(this::isPlayer)
                        .executes(this::executeDisbandWarning)
                        .then(Commands.literal("confirm").executes(this::executeDisbandConfirmed)))
                .then(Commands.literal("transferowner")
                        .requires(this::isPlayer)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild transferowner <player>"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                .executes(this::executeTransferOwner)))
                .then(Commands.literal("info")
                        .executes(this::executeInfoSelf)
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::executeInfoNamed)))
                .then(Commands.literal("list")
                        .executes(ctx -> executeList(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeList(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("plot")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild plot tp|cosmetic"))
                        .then(Commands.literal("tp").requires(this::isPlayer).executes(this::executePlotTeleport))
                        .then(plotCosmeticSubtree()))
                .then(adminSubtree())
                .build();
    }

    // ---- /guild create ------------------------------------------------------------------

    private int executeCreate(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name").strip();
        RigelConfig config = plugin.rigelConfig();

        dbExecutor.submit(() -> {
            try {
                GuildService.CreateOutcome outcome = guildService.create(
                        name, player.getUniqueId(), config.guildNameMinLength(), config.guildNameMaxLength(),
                        config.guildPlotWorldName(), GuildPlotWorldService.PlotGridSettings.fromConfig(config),
                        System.currentTimeMillis());
                switch (outcome.result()) {
                    case CREATED -> {
                        auditLogService.record(player.getUniqueId(), "GUILD_CREATE", null, name);
                        sync(() -> player.sendMessage(Component.text(
                                "Guild '" + name + "' created. You are the owner.", NamedTextColor.GREEN)));
                    }
                    case INVALID_NAME -> sync(() -> player.sendMessage(Component.text(
                            "Invalid guild name - must be " + plugin.rigelConfig().guildNameMinLength() + "-"
                                    + plugin.rigelConfig().guildNameMaxLength() + " characters and not resemble a staff rank.",
                            NamedTextColor.RED)));
                    case NAME_TAKEN -> sync(() -> player.sendMessage(
                            Component.text("A guild named '" + name + "' already exists.", NamedTextColor.RED)));
                    case ALREADY_IN_GUILD -> sync(() -> player.sendMessage(
                            Component.text("You're already in a guild - leave it first.", NamedTextColor.RED)));
                }
            } catch (SQLException e) {
                logAndNotifyFailure(player, "/guild create", e);
            }
        });
        return 1;
    }

    // ---- /guild invite, accept, deny -----------------------------------------------------

    private int executeInvite(CommandContext<CommandSourceStack> ctx) {
        Player sender = (Player) ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(Component.text(
                    "'" + targetName + "' must be online to invite them.", NamedTextColor.RED));
            return 0;
        }
        if (target.equals(sender)) {
            sender.sendMessage(Component.text("You can't invite yourself.", NamedTextColor.RED));
            return 0;
        }

        Optional<GuildRoster> senderRoster = guildService.rosterFor(sender.getUniqueId());
        if (senderRoster.isEmpty()) {
            sender.sendMessage(Component.text("You're not in a guild.", NamedTextColor.RED));
            return 0;
        }
        if (!senderRoster.get().isAtLeast(sender.getUniqueId(), GuildRole.OFFICER)) {
            sender.sendMessage(Component.text("Only the guild owner or an officer can invite.", NamedTextColor.RED));
            return 0;
        }
        if (guildService.rosterFor(target.getUniqueId()).isPresent()) {
            sender.sendMessage(Component.text(target.getName() + " is already in a guild.", NamedTextColor.RED));
            return 0;
        }

        GuildRoster roster = senderRoster.get();
        inviteManager.invite(target.getUniqueId(), roster.id(), roster.name(), sender.getUniqueId(), System.currentTimeMillis());
        sender.sendMessage(Component.text("Invited " + target.getName() + " to " + roster.name() + ".", NamedTextColor.GREEN));
        target.sendMessage(Component.text(
                sender.getName() + " invited you to join " + roster.name() + " - /guild accept or /guild deny (expires in 60s).",
                NamedTextColor.GOLD));
        return 1;
    }

    private int executeAccept(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        long now = System.currentTimeMillis();
        Optional<GuildInviteManager.PendingInvite> pending = inviteManager.pendingFor(player.getUniqueId(), now);
        if (pending.isEmpty()) {
            player.sendMessage(Component.text("You have no pending guild invite.", NamedTextColor.RED));
            return 0;
        }
        if (guildService.rosterFor(player.getUniqueId()).isPresent()) {
            player.sendMessage(Component.text("You're already in a guild.", NamedTextColor.RED));
            inviteManager.clear(player.getUniqueId());
            return 0;
        }

        GuildInviteManager.PendingInvite invite = pending.get();
        dbExecutor.submit(() -> {
            try {
                guildService.addMember(invite.guildId(), player.getUniqueId(), GuildRole.MEMBER, now);
                inviteManager.clear(player.getUniqueId());
                auditLogService.record(player.getUniqueId(), "GUILD_JOIN", null, invite.guildName());
                sync(() -> {
                    player.sendMessage(Component.text("You joined " + invite.guildName() + ".", NamedTextColor.GREEN));
                    Player inviter = Bukkit.getPlayer(invite.invitedBy());
                    if (inviter != null) {
                        inviter.sendMessage(Component.text(
                                player.getName() + " accepted your invite to " + invite.guildName() + ".", NamedTextColor.GREEN));
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(player, "/guild accept", e);
            }
        });
        return 1;
    }

    private int executeDeny(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        long now = System.currentTimeMillis();
        if (inviteManager.pendingFor(player.getUniqueId(), now).isEmpty()) {
            player.sendMessage(Component.text("You have no pending guild invite.", NamedTextColor.RED));
            return 0;
        }
        inviteManager.clear(player.getUniqueId());
        player.sendMessage(Component.text("Invite declined.", NamedTextColor.GRAY));
        return 1;
    }

    // ---- /guild kick, promote, demote -----------------------------------------------------

    private int executeKick(CommandContext<CommandSourceStack> ctx) {
        Player sender = (Player) ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        Optional<GuildRoster> senderRoster = guildService.rosterFor(sender.getUniqueId());
        if (senderRoster.isEmpty()) {
            sender.sendMessage(Component.text("You're not in a guild.", NamedTextColor.RED));
            return 0;
        }
        GuildRoster roster = senderRoster.get();
        GuildRole senderRole = roster.roleOf(sender.getUniqueId());
        if (senderRole == null || !senderRole.isAtLeast(GuildRole.OFFICER)) {
            sender.sendMessage(Component.text("Only the guild owner or an officer can kick.", NamedTextColor.RED));
            return 0;
        }

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuidOpt = resolveUuid(targetName);
                if (targetUuidOpt.isEmpty() || !roster.isMember(targetUuidOpt.get())) {
                    sync(() -> sender.sendMessage(Component.text(targetName + " isn't in your guild.", NamedTextColor.RED)));
                    return;
                }
                UUID targetUuid = targetUuidOpt.get();
                if (targetUuid.equals(sender.getUniqueId())) {
                    sync(() -> sender.sendMessage(Component.text("Use /guild leave to leave yourself.", NamedTextColor.RED)));
                    return;
                }
                GuildRole targetRole = roster.roleOf(targetUuid);
                // An OFFICER can only kick a MEMBER, never another OFFICER or the OWNER -
                // strictly outrank, matching punish.PunishModule's own staff-target-protection idiom.
                if (targetRole != null && targetRole.isAtLeast(senderRole)) {
                    sync(() -> sender.sendMessage(Component.text(
                            "You can't kick someone with equal or higher standing.", NamedTextColor.RED)));
                    return;
                }
                guildService.removeMember(roster.id(), targetUuid);
                auditLogService.record(sender.getUniqueId(), "GUILD_KICK", targetUuid, roster.name());
                sync(() -> {
                    sender.sendMessage(Component.text(targetName + " was kicked from " + roster.name() + ".", NamedTextColor.GREEN));
                    Player onlineTarget = Bukkit.getPlayer(targetUuid);
                    if (onlineTarget != null) {
                        onlineTarget.sendMessage(Component.text("You were kicked from " + roster.name() + ".", NamedTextColor.RED));
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/guild kick", e);
            }
        });
        return 1;
    }

    private int executeChangeRole(CommandContext<CommandSourceStack> ctx, boolean promote) {
        Player sender = (Player) ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        Optional<GuildRoster> senderRoster = guildService.rosterFor(sender.getUniqueId());
        if (senderRoster.isEmpty() || !senderRoster.get().roleOf(sender.getUniqueId()).equals(GuildRole.OWNER)) {
            sender.sendMessage(Component.text("Only the guild owner can " + (promote ? "promote" : "demote") + ".", NamedTextColor.RED));
            return 0;
        }
        GuildRoster roster = senderRoster.get();

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuidOpt = resolveUuid(targetName);
                if (targetUuidOpt.isEmpty() || !roster.isMember(targetUuidOpt.get())) {
                    sync(() -> sender.sendMessage(Component.text(targetName + " isn't in your guild.", NamedTextColor.RED)));
                    return;
                }
                UUID targetUuid = targetUuidOpt.get();
                GuildRole currentRole = roster.roleOf(targetUuid);
                GuildRole newRole = promote ? GuildRole.OFFICER : GuildRole.MEMBER;
                if (currentRole == GuildRole.OWNER) {
                    sync(() -> sender.sendMessage(Component.text(
                            "Use /guild transferowner to change who owns the guild.", NamedTextColor.RED)));
                    return;
                }
                if (currentRole == newRole) {
                    sync(() -> sender.sendMessage(Component.text(
                            targetName + " is already " + (promote ? "an officer." : "a member."), NamedTextColor.YELLOW)));
                    return;
                }
                guildService.updateRole(roster.id(), targetUuid, newRole);
                auditLogService.record(sender.getUniqueId(), promote ? "GUILD_PROMOTE" : "GUILD_DEMOTE", targetUuid, roster.name());
                sync(() -> {
                    sender.sendMessage(Component.text(
                            targetName + " is now " + (promote ? "an officer." : "a member."), NamedTextColor.GREEN));
                    Player onlineTarget = Bukkit.getPlayer(targetUuid);
                    if (onlineTarget != null) {
                        onlineTarget.sendMessage(Component.text(
                                "You were " + (promote ? "promoted to officer" : "demoted to member") + " in " + roster.name() + ".",
                                NamedTextColor.GOLD));
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/guild " + (promote ? "promote" : "demote"), e);
            }
        });
        return 1;
    }

    // ---- /guild leave, disband, transferowner ---------------------------------------------

    private int executeLeave(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Optional<GuildRoster> roster = guildService.rosterFor(player.getUniqueId());
        if (roster.isEmpty()) {
            player.sendMessage(Component.text("You're not in a guild.", NamedTextColor.RED));
            return 0;
        }
        if (roster.get().ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text(
                    "You're the owner - use /guild transferowner or /guild disband instead.", NamedTextColor.RED));
            return 0;
        }
        int guildId = roster.get().id();
        String guildName = roster.get().name();

        dbExecutor.submit(() -> {
            try {
                guildService.removeMember(guildId, player.getUniqueId());
                auditLogService.record(player.getUniqueId(), "GUILD_LEAVE", null, guildName);
                sync(() -> player.sendMessage(Component.text("You left " + guildName + ".", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotifyFailure(player, "/guild leave", e);
            }
        });
        return 1;
    }

    private int executeDisbandWarning(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Optional<GuildRoster> roster = guildService.rosterFor(player.getUniqueId());
        if (roster.isEmpty() || !roster.get().ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the guild owner can disband it.", NamedTextColor.RED));
            return 0;
        }
        player.sendMessage(Component.text(
                "This will PERMANENTLY disband " + roster.get().name() + " - every member is removed and the name"
                        + " becomes available again. Run '/guild disband confirm' to proceed.",
                NamedTextColor.RED));
        return 1;
    }

    private int executeDisbandConfirmed(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Optional<GuildRoster> rosterOpt = guildService.rosterFor(player.getUniqueId());
        if (rosterOpt.isEmpty() || !rosterOpt.get().ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the guild owner can disband it.", NamedTextColor.RED));
            return 0;
        }
        GuildRoster roster = rosterOpt.get();

        dbExecutor.submit(() -> {
            try {
                guildService.disband(roster.id());
                auditLogService.record(player.getUniqueId(), "GUILD_DISBAND", null, roster.name());
                sync(() -> {
                    player.sendMessage(Component.text(roster.name() + " has been disbanded.", NamedTextColor.GREEN));
                    for (UUID member : roster.members().keySet()) {
                        if (member.equals(player.getUniqueId())) {
                            continue;
                        }
                        Player onlineMember = Bukkit.getPlayer(member);
                        if (onlineMember != null) {
                            onlineMember.sendMessage(Component.text(
                                    roster.name() + " was disbanded by " + player.getName() + ".", NamedTextColor.RED));
                        }
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(player, "/guild disband", e);
            }
        });
        return 1;
    }

    private int executeTransferOwner(CommandContext<CommandSourceStack> ctx) {
        Player sender = (Player) ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        Optional<GuildRoster> senderRoster = guildService.rosterFor(sender.getUniqueId());
        if (senderRoster.isEmpty() || !senderRoster.get().ownerUuid().equals(sender.getUniqueId())) {
            sender.sendMessage(Component.text("Only the guild owner can transfer ownership.", NamedTextColor.RED));
            return 0;
        }
        GuildRoster roster = senderRoster.get();

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuidOpt = resolveUuid(targetName);
                if (targetUuidOpt.isEmpty() || !roster.isMember(targetUuidOpt.get())) {
                    sync(() -> sender.sendMessage(Component.text(
                            targetName + " must already be a member of your guild.", NamedTextColor.RED)));
                    return;
                }
                UUID newOwner = targetUuidOpt.get();
                guildService.transferOwner(roster.id(), sender.getUniqueId(), newOwner, System.currentTimeMillis());
                auditLogService.record(sender.getUniqueId(), "GUILD_TRANSFER_OWNER", newOwner, roster.name());
                sync(() -> {
                    sender.sendMessage(Component.text(targetName + " is now the owner of " + roster.name() + ".", NamedTextColor.GREEN));
                    Player onlineTarget = Bukkit.getPlayer(newOwner);
                    if (onlineTarget != null) {
                        onlineTarget.sendMessage(Component.text(
                                "You are now the owner of " + roster.name() + ".", NamedTextColor.GOLD));
                    }
                });
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/guild transferowner", e);
            }
        });
        return 1;
    }

    // ---- /guild info, list -----------------------------------------------------------------

    private int executeInfoSelf(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            return CommandUsage.show(sender, "/guild info <name>");
        }
        Optional<GuildRoster> roster = guildService.rosterFor(player.getUniqueId());
        if (roster.isEmpty()) {
            sender.sendMessage(Component.text("You're not in a guild. Use /guild info <name> to look one up.", NamedTextColor.YELLOW));
            return 0;
        }
        sendInfo(sender, roster.get());
        return 1;
    }

    private int executeInfoNamed(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        Optional<GuildRoster> roster = guildService.rosterByName(name);
        if (roster.isEmpty()) {
            sender.sendMessage(Component.text("No guild found named '" + name + "'.", NamedTextColor.RED));
            return 0;
        }
        sendInfo(sender, roster.get());
        return 1;
    }

    private void sendInfo(CommandSender sender, GuildRoster roster) {
        sender.sendMessage(Component.text(roster.name(), NamedTextColor.GOLD)
                .append(Component.text(" - " + roster.members().size() + " member(s)", NamedTextColor.GRAY)));
        for (var entry : roster.members().entrySet()) {
            String name = playerNameOrUuid(entry.getKey());
            sender.sendMessage(Component.text("  " + name + " - " + entry.getValue().name(), NamedTextColor.YELLOW));
        }
    }

    private int executeList(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        List<GuildRoster> all = guildService.list();
        if (all.isEmpty()) {
            sender.sendMessage(Component.text("No guilds exist yet.", NamedTextColor.GRAY));
            return 1;
        }
        int perPage = 10;
        int maxPage = Math.max(1, (all.size() + perPage - 1) / perPage);
        int clampedPage = Math.min(Math.max(page, 1), maxPage);
        int from = (clampedPage - 1) * perPage;
        int to = Math.min(from + perPage, all.size());

        sender.sendMessage(Component.text("Guilds (page " + clampedPage + "/" + maxPage + "):", NamedTextColor.GOLD));
        for (GuildRoster roster : all.subList(from, to)) {
            sender.sendMessage(Component.text(
                    "  " + roster.name() + " - " + roster.members().size() + " member(s)", NamedTextColor.YELLOW));
        }
        return 1;
    }

    // ---- /guild plot tp ---------------------------------------------------------------------

    private int executePlotTeleport(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Optional<GuildRoster> roster = guildService.rosterFor(player.getUniqueId());
        if (roster.isEmpty()) {
            player.sendMessage(Component.text("You're not in a guild.", NamedTextColor.RED));
            return 0;
        }
        Integer plotSlotIndex = roster.get().record().plotSlotIndex();
        if (plotSlotIndex == null) {
            player.sendMessage(Component.text("Your guild doesn't have a plot assigned yet.", NamedTextColor.RED));
            return 0;
        }
        Optional<Location> location = guildPlotWorldService.plotTeleportLocation(plugin, plotSlotIndex);
        if (location.isEmpty()) {
            player.sendMessage(Component.text("The guild plot world isn't ready yet.", NamedTextColor.RED));
            return 0;
        }
        player.teleport(location.get());
        player.sendMessage(Component.text("Teleported to your guild's plot.", NamedTextColor.GREEN));
        return 1;
    }

    // ---- /guild plot cosmetic ----------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> plotCosmeticSubtree() {
        return Commands.literal("cosmetic")
                .executes(this::executeCosmeticList)
                .then(Commands.literal("list").executes(this::executeCosmeticList))
                .then(Commands.literal("buy")
                        .requires(this::isPlayer)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild plot cosmetic buy <key>"))
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests(this::suggestCosmeticKeys)
                                .executes(this::executeCosmeticBuy)))
                .then(Commands.literal("apply")
                        .requires(this::isPlayer)
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild plot cosmetic apply <key>"))
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests(this::suggestCosmeticKeys)
                                .executes(this::executeCosmeticApply)))
                .build();
    }

    private CompletableFuture<Suggestions> suggestCosmeticKeys(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (PlotCosmetic cosmetic : PlotCosmetic.values()) {
            if (cosmetic.key().startsWith(remaining)) {
                builder.suggest(cosmetic.key());
            }
        }
        return builder.buildFuture();
    }

    /** Open to anyone - shows the sender's own guild's owned cosmetics if they're in one, otherwise just the catalog. */
    private int executeCosmeticList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<GuildRoster> roster = sender instanceof Player player ? guildService.rosterFor(player.getUniqueId()) : Optional.empty();
        if (roster.isEmpty()) {
            sendCosmeticCatalog(sender, Set.of());
            return 1;
        }

        int guildId = roster.get().id();
        dbExecutor.submit(() -> {
            try {
                Set<String> owned = plotCosmeticService.purchasedKeysFor(guildId);
                sync(() -> sendCosmeticCatalog(sender, owned));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/guild plot cosmetic list", e);
            }
        });
        return 1;
    }

    private void sendCosmeticCatalog(CommandSender sender, Set<String> ownedKeys) {
        RigelConfig config = plugin.rigelConfig();
        sender.sendMessage(Component.text("Plot cosmetics:", NamedTextColor.GOLD));
        for (PlotCosmetic cosmetic : PlotCosmetic.values()) {
            boolean owned = ownedKeys.contains(cosmetic.key());
            String priceText = cosmetic.price() == 0 ? "free" : formatAmount(cosmetic.price(), config);
            sender.sendMessage(Component.text(
                    "  " + cosmetic.key() + " - " + cosmetic.displayName() + " ("
                            + cosmetic.category().name().toLowerCase(Locale.ROOT) + ", " + priceText + ")"
                            + (owned ? " [owned]" : ""),
                    owned ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        }
    }

    private int executeCosmeticBuy(CommandContext<CommandSourceStack> ctx) {
        Player sender = (Player) ctx.getSource().getSender();
        RigelConfig config = plugin.rigelConfig();
        Optional<PlotCosmetic> cosmeticOpt = PlotCosmetic.byKey(StringArgumentType.getString(ctx, "key"));
        if (cosmeticOpt.isEmpty()) {
            sender.sendMessage(Component.text("No plot cosmetic found with that key - see /guild plot cosmetic list.", NamedTextColor.RED));
            return 0;
        }
        PlotCosmetic cosmetic = cosmeticOpt.get();

        Optional<GuildRoster> rosterOpt = guildService.rosterFor(sender.getUniqueId());
        if (rosterOpt.isEmpty()) {
            sender.sendMessage(Component.text("You're not in a guild.", NamedTextColor.RED));
            return 0;
        }
        GuildRoster roster = rosterOpt.get();
        GuildRole role = roster.roleOf(sender.getUniqueId());
        if (role == null || !role.isAtLeast(GuildRole.OFFICER)) {
            sender.sendMessage(Component.text("Only the guild owner or an officer can buy plot cosmetics.", NamedTextColor.RED));
            return 0;
        }
        Integer plotSlotIndex = roster.record().plotSlotIndex();
        if (plotSlotIndex == null) {
            sender.sendMessage(Component.text("Your guild doesn't have a plot assigned yet.", NamedTextColor.RED));
            return 0;
        }

        dbExecutor.submit(() -> {
            try {
                PlotCosmeticService.BuyOutcome outcome =
                        plotCosmeticService.buy(roster.id(), sender.getUniqueId(), cosmetic, System.currentTimeMillis());
                switch (outcome.result()) {
                    case INSUFFICIENT_FUNDS -> sync(() -> sender.sendMessage(Component.text(
                            "You don't have enough " + config.economyCurrencyNamePlural() + " ("
                                    + formatAmount(cosmetic.price(), config) + " needed).",
                            NamedTextColor.RED)));
                    case PURCHASED -> {
                        auditLogService.record(
                                sender.getUniqueId(), "GUILD_PLOT_COSMETIC_BUY", null, roster.name() + ":" + cosmetic.key());
                        applyCosmeticAndNotify(sender, plotSlotIndex, cosmetic, config,
                                "Purchased and applied " + cosmetic.displayName() + " for "
                                        + formatAmount(cosmetic.price(), config) + ".");
                    }
                    case ALREADY_OWNED_REAPPLIED -> applyCosmeticAndNotify(sender, plotSlotIndex, cosmetic, config,
                            "Re-applied " + cosmetic.displayName() + " (already owned - free).");
                }
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/guild plot cosmetic buy", e);
            }
        });
        return 1;
    }

    private int executeCosmeticApply(CommandContext<CommandSourceStack> ctx) {
        Player sender = (Player) ctx.getSource().getSender();
        RigelConfig config = plugin.rigelConfig();
        Optional<PlotCosmetic> cosmeticOpt = PlotCosmetic.byKey(StringArgumentType.getString(ctx, "key"));
        if (cosmeticOpt.isEmpty()) {
            sender.sendMessage(Component.text("No plot cosmetic found with that key - see /guild plot cosmetic list.", NamedTextColor.RED));
            return 0;
        }
        PlotCosmetic cosmetic = cosmeticOpt.get();

        Optional<GuildRoster> rosterOpt = guildService.rosterFor(sender.getUniqueId());
        if (rosterOpt.isEmpty()) {
            sender.sendMessage(Component.text("You're not in a guild.", NamedTextColor.RED));
            return 0;
        }
        GuildRoster roster = rosterOpt.get();
        GuildRole role = roster.roleOf(sender.getUniqueId());
        if (role == null || !role.isAtLeast(GuildRole.OFFICER)) {
            sender.sendMessage(Component.text("Only the guild owner or an officer can apply plot cosmetics.", NamedTextColor.RED));
            return 0;
        }
        Integer plotSlotIndex = roster.record().plotSlotIndex();
        if (plotSlotIndex == null) {
            sender.sendMessage(Component.text("Your guild doesn't have a plot assigned yet.", NamedTextColor.RED));
            return 0;
        }

        dbExecutor.submit(() -> {
            try {
                if (!plotCosmeticService.isPurchased(roster.id(), cosmetic)) {
                    sync(() -> sender.sendMessage(Component.text(
                            "Your guild doesn't own " + cosmetic.displayName() + " yet - /guild plot cosmetic buy "
                                    + cosmetic.key() + ".",
                            NamedTextColor.RED)));
                    return;
                }
                applyCosmeticAndNotify(sender, plotSlotIndex, cosmetic, config, "Applied " + cosmetic.displayName() + ".");
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/guild plot cosmetic apply", e);
            }
        });
        return 1;
    }

    /** Hops to the main thread to actually place blocks (Bukkit API) - called from within a {@code dbExecutor} task after a successful buy/apply check. */
    private void applyCosmeticAndNotify(
            Player sender, int plotSlotIndex, PlotCosmetic cosmetic, RigelConfig config, String message) {
        sync(() -> {
            World world = Bukkit.getWorld(config.guildPlotWorldName());
            if (world == null) {
                sender.sendMessage(Component.text("The guild plot world isn't ready yet.", NamedTextColor.RED));
                return;
            }
            PlotGridAllocator.PlotBounds bounds = PlotGridAllocator.boundsForSlot(
                    plotSlotIndex, config.guildPlotSize(), config.guildPlotGap(), config.guildPlotGridColumns());
            List<PlotCosmeticApplier.BlockWrite> writes =
                    PlotCosmeticApplier.blockWritesFor(cosmetic, bounds, config.guildPlotGroundY());
            PlotCosmeticApplier.apply(world, writes);
            sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
        });
    }

    private String formatAmount(long amount, RigelConfig config) {
        return amount + " " + (amount == 1 ? config.economyCurrencyNameSingular() : config.economyCurrencyNamePlural());
    }

    // ---- /guild admin (Moderator+) ---------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> adminSubtree() {
        return Commands.literal("admin")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(
                        ctx.getSource().getSender(), "/guild admin disband|kick|setowner|plotbypass <name> ..."))
                .then(Commands.literal("plotbypass")
                        .requires(source -> hasRank(source, "senior_admin"))
                        .executes(this::executePlotBypassToggle))
                .then(Commands.literal("disband")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild admin disband <name>"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(this::executeAdminDisbandWarning)
                                .then(Commands.literal("confirm").executes(this::executeAdminDisbandConfirmed))))
                .then(Commands.literal("kick")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild admin kick <name> <player>"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild admin kick <name> <player>"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                        .executes(this::executeAdminKick))))
                .then(Commands.literal("setowner")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild admin setowner <name> <player>"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/guild admin setowner <name> <player>"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                        .executes(this::executeAdminSetOwner))))
                .build();
    }

    /**
     * Toggles the calling Senior Admin's {@code guild.plot.GuildPlotBypassService} flag -
     * see {@code guild.plot.GuildPlotBoundaryGuard} for the actual enforcement this
     * unlocks. Player-only (console has no plot to stand in and break blocks from) -
     * checked here rather than via {@code .requires()} so a console/RCON caller gets a
     * clear message instead of Brigadier's generic "unknown command", matching every
     * other console-vs-player split in this codebase.
     */
    private int executePlotBypassToggle(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "/guild admin plotbypass can only be run in-game - console/RCON already bypasses everything.",
                    NamedTextColor.RED));
            return 0;
        }
        boolean nowOn = guildPlotBypassService.toggle(player.getUniqueId());
        player.sendMessage(nowOn
                ? Component.text(
                        "Guild plot-world bypass ENABLED - you can now build/break outside your own plot"
                                + " (including borders/roads) until you toggle this off or log out.",
                        NamedTextColor.GOLD)
                : Component.text(
                        "Guild plot-world bypass DISABLED - you're now restricted to your own plot again, like"
                                + " everyone else.",
                        NamedTextColor.GREEN));
        return 1;
    }

    private int executeAdminDisbandWarning(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        if (guildService.rosterByName(name).isEmpty()) {
            sender.sendMessage(Component.text("No guild found named '" + name + "'.", NamedTextColor.RED));
            return 0;
        }
        sender.sendMessage(Component.text(
                "This will PERMANENTLY disband '" + name + "'. Run '/guild admin disband " + name + " confirm' to proceed.",
                NamedTextColor.RED));
        return 1;
    }

    private int executeAdminDisbandConfirmed(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        UUID actorUuid = actorUuid(sender);
        Optional<GuildRoster> rosterOpt = guildService.rosterByName(name);
        if (rosterOpt.isEmpty()) {
            sender.sendMessage(Component.text("No guild found named '" + name + "'.", NamedTextColor.RED));
            return 0;
        }
        GuildRoster roster = rosterOpt.get();

        dbExecutor.submit(() -> {
            try {
                guildService.disband(roster.id());
                auditLogService.record(actorUuid, "GUILD_ADMIN_DISBAND", null, roster.name());
                sync(() -> sender.sendMessage(Component.text(roster.name() + " has been disbanded.", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/guild admin disband", e);
            }
        });
        return 1;
    }

    private int executeAdminKick(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String guildName = StringArgumentType.getString(ctx, "name");
        String targetName = StringArgumentType.getString(ctx, "player");
        UUID actorUuid = actorUuid(sender);

        Optional<GuildRoster> rosterOpt = guildService.rosterByName(guildName);
        if (rosterOpt.isEmpty()) {
            sender.sendMessage(Component.text("No guild found named '" + guildName + "'.", NamedTextColor.RED));
            return 0;
        }
        GuildRoster roster = rosterOpt.get();

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuidOpt = resolveUuid(targetName);
                if (targetUuidOpt.isEmpty() || !roster.isMember(targetUuidOpt.get())) {
                    sync(() -> sender.sendMessage(Component.text(targetName + " isn't in " + guildName + ".", NamedTextColor.RED)));
                    return;
                }
                guildService.removeMember(roster.id(), targetUuidOpt.get());
                auditLogService.record(actorUuid, "GUILD_ADMIN_KICK", targetUuidOpt.get(), roster.name());
                sync(() -> sender.sendMessage(Component.text(
                        targetName + " was removed from " + roster.name() + ".", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/guild admin kick", e);
            }
        });
        return 1;
    }

    private int executeAdminSetOwner(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String guildName = StringArgumentType.getString(ctx, "name");
        String targetName = StringArgumentType.getString(ctx, "player");
        UUID actorUuid = actorUuid(sender);

        Optional<GuildRoster> rosterOpt = guildService.rosterByName(guildName);
        if (rosterOpt.isEmpty()) {
            sender.sendMessage(Component.text("No guild found named '" + guildName + "'.", NamedTextColor.RED));
            return 0;
        }
        GuildRoster roster = rosterOpt.get();

        dbExecutor.submit(() -> {
            try {
                Optional<UUID> targetUuidOpt = resolveUuid(targetName);
                if (targetUuidOpt.isEmpty()) {
                    sync(() -> sendPlayerNotFound(sender, targetName));
                    return;
                }
                UUID newOwner = targetUuidOpt.get();
                if (!roster.isMember(newOwner)) {
                    // An admin override, unlike the self-service /guild transferowner - add
                    // them as a member first so ownership always lands on an actual member.
                    guildService.addMember(roster.id(), newOwner, GuildRole.MEMBER, System.currentTimeMillis());
                }
                guildService.transferOwner(roster.id(), roster.ownerUuid(), newOwner, System.currentTimeMillis());
                auditLogService.record(actorUuid, "GUILD_ADMIN_SETOWNER", newOwner, roster.name());
                sync(() -> sender.sendMessage(Component.text(
                        targetName + " is now the owner of " + roster.name() + ".", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotifyFailure(sender, "/guild admin setowner", e);
            }
        });
        return 1;
    }

    // ---- shared helpers ---------------------------------------------------------------------

    private boolean isPlayer(CommandSourceStack source) {
        return source.getSender() instanceof Player;
    }

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }

    @Nullable
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

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
