package org.rigelmc.protect.area;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
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
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.command.PlayerSuggestions;
import org.rigelmc.rank.PermissionGate;

/**
 * The full {@code /protectarea} Brigadier tree - split out from {@link ProtectAreaModule}
 * purely because of subcommand count, matching that module's javadoc. All subcommands are
 * Moderator+ (console always allowed), translating TFM's own "any admin" floor onto this
 * project's rank ladder - matching {@code protect.worldedit.WorldEditAbuseGuard}/{@code
 * protect.antigrief.EntityCleanupModule}'s existing precedent.
 */
public final class ProtectAreaCommand {

    private final ProtectAreaService service;
    private final PermissionGate permissionGate;
    private final AuditLogService auditLogService;
    private final ExecutorService dbExecutor;
    private RigelMCMod plugin;

    public ProtectAreaCommand(
            @NotNull ProtectAreaService service, @NotNull PermissionGate permissionGate,
            @NotNull AuditLogService auditLogService, @NotNull ExecutorService dbExecutor) {
        this.service = service;
        this.permissionGate = permissionGate;
        this.auditLogService = auditLogService;
        this.dbExecutor = dbExecutor;
    }

    void bind(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @NotNull
    LiteralCommandNode<CommandSourceStack> build(@NotNull String rootLiteral) {
        return Commands.literal(rootLiteral)
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(),
                        "/" + rootLiteral + " <create|update|delete|clear|rename|info|list|flag|"
                                + "addmember|removemember|priority|setowner|enable|disable|tp>"))
                .then(createOrUpdate("create"))
                .then(createOrUpdate("update"))
                .then(Commands.literal("delete")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea delete <name>"))
                        .then(nameArg().executes(this::executeDeleteWarning)
                                .then(Commands.literal("confirm").executes(this::executeDeleteConfirmed))))
                .then(Commands.literal("clear")
                        .executes(this::executeClearWarning)
                        .then(Commands.literal("confirm").executes(this::executeClearConfirmed)))
                .then(Commands.literal("rename")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea rename <name> <newName>"))
                        .then(nameArg()
                                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea rename <name> <newName>"))
                                .then(Commands.argument("newName", StringArgumentType.word()).executes(this::executeRename))))
                .then(Commands.literal("info")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea info <name> [-show]"))
                        .then(nameArg().executes(ctx -> executeInfo(ctx, false))
                                .then(Commands.literal("-show").executes(ctx -> executeInfo(ctx, true)))))
                .then(Commands.literal("list")
                        .executes(ctx -> executeList(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeList(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("flag")
                        .executes(ctx -> CommandUsage.show(
                                ctx.getSource().getSender(), "/protectarea flag <name> <flag> <allow|deny|default>"))
                        .then(nameArg()
                                .executes(ctx -> CommandUsage.show(
                                        ctx.getSource().getSender(), "/protectarea flag <name> <flag> <allow|deny|default>"))
                                .then(Commands.argument("flag", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (AreaFlag flag : AreaFlag.values()) {
                                                builder.suggest(flag.key());
                                            }
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.literal("allow").executes(ctx -> executeFlag(ctx, Boolean.TRUE)))
                                        .then(Commands.literal("deny").executes(ctx -> executeFlag(ctx, Boolean.FALSE)))
                                        .then(Commands.literal("default").executes(ctx -> executeFlag(ctx, null))))))
                .then(Commands.literal("addmember")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea addmember <name> <player>"))
                        .then(nameArg()
                                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea addmember <name> <player>"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                        .executes(ctx -> executeMember(ctx, true)))))
                .then(Commands.literal("removemember")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea removemember <name> <player>"))
                        .then(nameArg()
                                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea removemember <name> <player>"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                        .executes(ctx -> executeMember(ctx, false)))))
                .then(Commands.literal("priority")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea priority <name> <int>"))
                        .then(nameArg()
                                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea priority <name> <int>"))
                                .then(Commands.argument("priority", IntegerArgumentType.integer())
                                        .executes(this::executePriority))))
                .then(Commands.literal("setowner")
                        .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea setowner <name> <player>"))
                        .then(nameArg()
                                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/protectarea setowner <name> <player>"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                        .executes(this::executeSetOwner))))
                .then(Commands.literal("enable").then(nameArg().executes(ctx -> executeSetEnabled(ctx, true))))
                .then(Commands.literal("disable").then(nameArg().executes(ctx -> executeSetEnabled(ctx, false))))
                .then(Commands.literal("tp").then(nameArg().executes(this::executeTeleport)))
                .build();
    }

    // ---- create / update ------------------------------------------------------------------

    private com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> createOrUpdate(String literal) {
        boolean isUpdate = literal.equals("update");
        String usage = "/protectarea " + literal + " <name> <x1> <y1> <z1> <x2> <y2> <z2> [-nest]  (or: /protectarea "
                + literal + " <name> -wand [-nest] to use your current WorldEdit selection)";

        var nest = Commands.literal("-nest").executes(ctx -> executeCreateOrUpdate(ctx, isUpdate, true));
        var z2 = Commands.argument("z2", IntegerArgumentType.integer())
                .executes(ctx -> executeCreateOrUpdate(ctx, isUpdate, false))
                .then(nest);
        var y2 = Commands.argument("y2", IntegerArgumentType.integer()).then(z2);
        var x2 = Commands.argument("x2", IntegerArgumentType.integer()).then(y2);
        var z1 = Commands.argument("z1", IntegerArgumentType.integer()).then(x2);
        var y1 = Commands.argument("y1", IntegerArgumentType.integer()).then(z1);
        var x1 = Commands.argument("x1", IntegerArgumentType.integer()).then(y1);

        var wandNest = Commands.literal("-nest").executes(ctx -> executeCreateOrUpdateFromWand(ctx, isUpdate, true));
        var wand = Commands.literal("-wand")
                .executes(ctx -> executeCreateOrUpdateFromWand(ctx, isUpdate, false))
                .then(wandNest);

        var name = nameArg().executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), usage)).then(x1).then(wand);

        return Commands.literal(literal)
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), usage))
                .then(name);
    }

    private int executeCreateOrUpdate(CommandContext<CommandSourceStack> ctx, boolean isUpdate, boolean allowNest) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "/protectarea " + (isUpdate ? "update" : "create") + " can only be run in-game (no world to"
                            + " infer from console) - explicit corner coordinates, no WorldEdit needed.",
                    NamedTextColor.RED));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        String world = player.getWorld().getName();
        int x1 = IntegerArgumentType.getInteger(ctx, "x1");
        int y1 = IntegerArgumentType.getInteger(ctx, "y1");
        int z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2");
        int y2 = IntegerArgumentType.getInteger(ctx, "y2");
        int z2 = IntegerArgumentType.getInteger(ctx, "z2");
        return dispatchCreateOrUpdate(
                sender, player, isUpdate, allowNest, name, world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /**
     * The WorldEdit-selection-driven form ({@code -wand}) - explicit corner coordinates
     * (the other form, above) need no WorldEdit dependency at all, so this is purely an
     * added convenience once WorldEdit/FAWE is actually installed, gated on {@link
     * #resolveWorldEditPlugin()} finding one, matching TFM's own selection-reading pattern
     * (studied directly: {@code WorldEditHook#onSizeSensitiveCommand}) - {@code
     * LocalSession#getSelection(World)}, which throws {@link IncompleteRegionException} if
     * the player hasn't finished making one.
     */
    private int executeCreateOrUpdateFromWand(CommandContext<CommandSourceStack> ctx, boolean isUpdate, boolean allowNest) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "/protectarea " + (isUpdate ? "update" : "create") + " -wand can only be run in-game.",
                    NamedTextColor.RED));
            return 0;
        }
        WorldEditPlugin worldEditPlugin = resolveWorldEditPlugin();
        if (worldEditPlugin == null) {
            sender.sendMessage(Component.text(
                    "Neither WorldEdit nor FAWE is installed - use explicit corner coordinates instead: /protectarea "
                            + (isUpdate ? "update" : "create") + " <name> <x1> <y1> <z1> <x2> <y2> <z2> [-nest].",
                    NamedTextColor.RED));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        com.sk89q.worldedit.entity.Player wePlayer = worldEditPlugin.wrapPlayer(player);
        Region region;
        try {
            region = WorldEdit.getInstance().getSessionManager().get(wePlayer).getSelection(wePlayer.getWorld());
        } catch (IncompleteRegionException e) {
            sender.sendMessage(Component.text(
                    "You don't have a complete WorldEdit selection - make one first (//pos1 and //pos2, or a wand).",
                    NamedTextColor.RED));
            return 0;
        }

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        return dispatchCreateOrUpdate(
                sender, player, isUpdate, allowNest, name, player.getWorld().getName(),
                min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }

    /** Resolves whichever WorldEdit-compatible plugin is loaded - TFM's own pattern, ported directly. */
    @Nullable
    private static WorldEditPlugin resolveWorldEditPlugin() {
        org.bukkit.plugin.Plugin we = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (we instanceof WorldEditPlugin worldEditPlugin && we.isEnabled()) {
            return worldEditPlugin;
        }
        we = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        if (we instanceof WorldEditPlugin worldEditPlugin && we.isEnabled()) {
            return worldEditPlugin;
        }
        return null;
    }

    private int dispatchCreateOrUpdate(
            CommandSender sender, Player player, boolean isUpdate, boolean allowNest, String name, String world,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        UUID actor = player.getUniqueId();
        dbExecutor.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                if (isUpdate) {
                    Optional<AreaRegion> existing = service.find(name);
                    if (existing.isEmpty()) {
                        sync(() -> notFound(sender, name));
                        return;
                    }
                    ProtectAreaService.UpdateOutcome outcome =
                            service.update(existing.get(), world, minX, minY, minZ, maxX, maxY, maxZ, allowNest, actor, now);
                    if (outcome.result() == ProtectAreaService.UpdateResult.OVERLAP) {
                        sync(() -> sendOverlapDenied(sender, outcome.conflicts()));
                        return;
                    }
                    auditLogService.record(actor, "PROTECTAREA_UPDATE", null, name);
                    sync(() -> sender.sendMessage(Component.text("Updated '" + name + "'.", NamedTextColor.GREEN)));
                } else {
                    ProtectAreaService.CreateOutcome outcome =
                            service.create(name, world, minX, minY, minZ, maxX, maxY, maxZ, allowNest, actor, now);
                    switch (outcome.result()) {
                        case DUPLICATE_NAME -> sync(() -> sender.sendMessage(Component.text(
                                "A region named '" + name + "' already exists.", NamedTextColor.RED)));
                        case OVERLAP -> sync(() -> sendOverlapDenied(sender, outcome.conflicts()));
                        case OK -> {
                            auditLogService.record(actor, "PROTECTAREA_CREATE", null, name);
                            sync(() -> sender.sendMessage(Component.text("Created '" + name + "'.", NamedTextColor.GREEN)));
                        }
                        default -> throw new IllegalStateException("Unhandled create result: " + outcome.result());
                    }
                }
            } catch (SQLException e) {
                logAndNotify(sender, "/protectarea " + (isUpdate ? "update" : "create"), e);
            }
        });
        return 1;
    }

    private void sendOverlapDenied(CommandSender sender, List<AreaRegion> conflicts) {
        String names = conflicts.stream().map(AreaRegion::name).reduce((a, b) -> a + ", " + b).orElse("?");
        sender.sendMessage(Component.text(
                "That would overlap: " + names + ". Pass -nest at the end if this is intentional (it will be"
                        + " nested with a higher priority than the region(s) it overlaps).",
                NamedTextColor.RED));
    }

    // ---- delete / clear ---------------------------------------------------------------------

    private int executeDeleteWarning(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ctx.getSource().getSender().sendMessage(Component.text(
                "This will permanently delete the protected region '" + name + "'. This cannot be undone."
                        + " Run '/protectarea delete " + name + " confirm' to proceed.",
                NamedTextColor.RED));
        return 0;
    }

    private int executeDeleteConfirmed(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        Optional<AreaRegion> existing = service.find(name);
        if (existing.isEmpty()) {
            notFound(sender, name);
            return 0;
        }
        AreaRegion region = existing.get();
        UUID actor = actorUuid(sender);
        dbExecutor.submit(() -> {
            try {
                service.delete(region);
                auditLogService.record(actor, "PROTECTAREA_DELETE", null, name);
                sync(() -> sender.sendMessage(Component.text("Deleted '" + name + "'.", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotify(sender, "/protectarea delete", e);
            }
        });
        return 1;
    }

    private int executeClearWarning(CommandContext<CommandSourceStack> ctx) {
        int count = service.list().size();
        ctx.getSource().getSender().sendMessage(Component.text(
                "This will permanently delete ALL " + count + " protected region(s) on the server. This cannot"
                        + " be undone. Run '/protectarea clear confirm' to proceed.",
                NamedTextColor.RED));
        return 0;
    }

    private int executeClearConfirmed(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        UUID actor = actorUuid(sender);
        dbExecutor.submit(() -> {
            try {
                int count = service.list().size();
                service.clearAll();
                auditLogService.record(actor, "PROTECTAREA_CLEAR", null, "cleared " + count + " region(s)");
                sync(() -> sender.sendMessage(Component.text(
                        "Cleared " + count + " protected region(s).", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotify(sender, "/protectarea clear", e);
            }
        });
        return 1;
    }

    // ---- rename / priority / setowner / enable-disable ---------------------------------------

    private int executeRename(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        String newName = StringArgumentType.getString(ctx, "newName");
        Optional<AreaRegion> existing = service.find(name);
        if (existing.isEmpty()) {
            notFound(sender, name);
            return 0;
        }
        if (service.find(newName).isPresent()) {
            sender.sendMessage(Component.text("A region named '" + newName + "' already exists.", NamedTextColor.RED));
            return 0;
        }
        AreaRegion region = existing.get();
        UUID actor = actorUuid(sender);
        dbExecutor.submit(() -> {
            try {
                service.rename(region, newName, actor, System.currentTimeMillis());
                auditLogService.record(actor, "PROTECTAREA_RENAME", null, name + " -> " + newName);
                sync(() -> sender.sendMessage(Component.text(
                        "Renamed '" + name + "' to '" + newName + "'.", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotify(sender, "/protectarea rename", e);
            }
        });
        return 1;
    }

    private int executePriority(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        int priority = IntegerArgumentType.getInteger(ctx, "priority");
        Optional<AreaRegion> existing = service.find(name);
        if (existing.isEmpty()) {
            notFound(sender, name);
            return 0;
        }
        AreaRegion region = existing.get();
        UUID actor = actorUuid(sender);
        dbExecutor.submit(() -> {
            try {
                service.setPriority(region, priority, actor, System.currentTimeMillis());
                sync(() -> sender.sendMessage(Component.text(
                        "Set '" + name + "'s priority to " + priority + ".", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotify(sender, "/protectarea priority", e);
            }
        });
        return 1;
    }

    private int executeSetOwner(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(Component.text("No online player found matching '" + targetName + "'.", NamedTextColor.RED));
            return 0;
        }
        Optional<AreaRegion> existing = service.find(name);
        if (existing.isEmpty()) {
            notFound(sender, name);
            return 0;
        }
        AreaRegion region = existing.get();
        UUID actor = actorUuid(sender);
        dbExecutor.submit(() -> {
            try {
                service.setOwner(region, target.getUniqueId(), actor, System.currentTimeMillis());
                sync(() -> sender.sendMessage(Component.text(
                        "Set '" + name + "'s owner to " + target.getName() + ".", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotify(sender, "/protectarea setowner", e);
            }
        });
        return 1;
    }

    private int executeSetEnabled(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        Optional<AreaRegion> existing = service.find(name);
        if (existing.isEmpty()) {
            notFound(sender, name);
            return 0;
        }
        AreaRegion region = existing.get();
        UUID actor = actorUuid(sender);
        dbExecutor.submit(() -> {
            try {
                service.setEnabled(region, enabled, actor, System.currentTimeMillis());
                sync(() -> sender.sendMessage(Component.text(
                        (enabled ? "Enabled" : "Disabled") + " '" + name + "'.", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotify(sender, "/protectarea " + (enabled ? "enable" : "disable"), e);
            }
        });
        return 1;
    }

    // ---- flag / members ---------------------------------------------------------------------

    private int executeFlag(CommandContext<CommandSourceStack> ctx, Boolean value) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        String flagKey = StringArgumentType.getString(ctx, "flag");
        AreaFlag flag = AreaFlag.byKey(flagKey);
        if (flag == null) {
            sender.sendMessage(Component.text("Unknown flag '" + flagKey + "'. Valid flags: "
                    + java.util.Arrays.stream(AreaFlag.values()).map(AreaFlag::key)
                            .reduce((a, b) -> a + ", " + b).orElse("") + ".",
                    NamedTextColor.RED));
            return 0;
        }
        Optional<AreaRegion> existing = service.find(name);
        if (existing.isEmpty()) {
            notFound(sender, name);
            return 0;
        }
        AreaRegion region = existing.get();
        dbExecutor.submit(() -> {
            try {
                service.setFlag(region, flag, value);
                String stateText = value == null ? "reset to its default" : value ? "set to allow" : "set to deny";
                sync(() -> sender.sendMessage(Component.text(
                        "'" + name + "'s " + flag.key() + " flag " + stateText + ".", NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotify(sender, "/protectarea flag", e);
            }
        });
        return 1;
    }

    private int executeMember(CommandContext<CommandSourceStack> ctx, boolean add) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(Component.text("No online player found matching '" + targetName + "'.", NamedTextColor.RED));
            return 0;
        }
        Optional<AreaRegion> existing = service.find(name);
        if (existing.isEmpty()) {
            notFound(sender, name);
            return 0;
        }
        AreaRegion region = existing.get();
        UUID actor = actorUuid(sender);
        dbExecutor.submit(() -> {
            try {
                if (add) {
                    service.addMember(region, target.getUniqueId(), actor, System.currentTimeMillis());
                } else {
                    service.removeMember(region, target.getUniqueId());
                }
                sync(() -> sender.sendMessage(Component.text(
                        (add ? "Added " : "Removed ") + target.getName() + (add ? " to" : " from")
                                + " '" + name + "'s member list.",
                        NamedTextColor.GREEN)));
            } catch (SQLException e) {
                logAndNotify(sender, "/protectarea " + (add ? "addmember" : "removemember"), e);
            }
        });
        return 1;
    }

    // ---- info / list / tp ---------------------------------------------------------------------

    private int executeInfo(CommandContext<CommandSourceStack> ctx, boolean show) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        Optional<AreaRegion> existing = service.find(name);
        if (existing.isEmpty()) {
            notFound(sender, name);
            return 0;
        }
        AreaRegion region = existing.get();
        sender.sendMessage(formatInfo(region));
        if (!show) {
            return 1;
        }
        if (!plugin.rigelConfig().protectAreaVisualizeEnabled()) {
            sender.sendMessage(Component.text("Boundary visualization is disabled on this server.", NamedTextColor.YELLOW));
        } else if (sender instanceof Player player) {
            AreaBoundaryVisualizer.show(plugin, player, region);
        } else {
            sender.sendMessage(Component.text("-show only works for an in-game sender.", NamedTextColor.YELLOW));
        }
        return 1;
    }

    @NotNull
    private Component formatInfo(AreaRegion region) {
        AreaRecord record = region.record();
        Component message = Component.text("'" + region.name() + "' ", NamedTextColor.GOLD)
                .append(Component.text("(" + record.world() + ", priority " + record.priority()
                        + (record.enabled() ? "" : ", DISABLED") + ")", NamedTextColor.GRAY))
                .appendNewline()
                .append(Component.text("  Bounds: ", NamedTextColor.GRAY))
                .append(Component.text("(" + record.minX() + ", " + record.minY() + ", " + record.minZ() + ") to ("
                        + record.maxX() + ", " + record.maxY() + ", " + record.maxZ() + ")", NamedTextColor.WHITE))
                .appendNewline()
                .append(Component.text("  Owner: ", NamedTextColor.GRAY))
                .append(Component.text(region.owner() != null ? region.owner().toString() : "none", NamedTextColor.WHITE))
                .appendNewline()
                .append(Component.text("  Members: ", NamedTextColor.GRAY))
                .append(Component.text(region.members().isEmpty() ? "none" : region.members().size() + " player(s)",
                        NamedTextColor.WHITE))
                .appendNewline()
                .append(Component.text("  Flags: ", NamedTextColor.GRAY));
        StringBuilder flags = new StringBuilder();
        for (AreaFlag flag : AreaFlag.values()) {
            if (!flags.isEmpty()) {
                flags.append(", ");
            }
            flags.append(flag.key()).append('=').append(region.effectiveFlag(flag) ? "allow" : "deny");
        }
        return message.append(Component.text(flags.toString(), NamedTextColor.WHITE));
    }

    private static final int PAGE_SIZE_FALLBACK = 8;

    private int executeList(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        List<AreaRegion> all = service.list();
        if (all.isEmpty()) {
            sender.sendMessage(Component.text("No protected regions exist yet.", NamedTextColor.GRAY));
            return 1;
        }
        int pageSize = plugin != null ? plugin.rigelConfig().protectAreaListPageSize() : PAGE_SIZE_FALLBACK;
        int totalPages = (all.size() + pageSize - 1) / pageSize;
        int clampedPage = Math.max(1, Math.min(page, totalPages));
        int from = (clampedPage - 1) * pageSize;
        int to = Math.min(from + pageSize, all.size());

        Component message = Component.text(
                "Protected regions (" + all.size() + ", page " + clampedPage + "/" + totalPages + "):", NamedTextColor.GOLD);
        for (AreaRegion region : all.subList(from, to)) {
            String line = "  " + region.name() + " (" + region.record().world() + ")"
                    + (region.enabled() ? "" : " [disabled]");
            message = message.appendNewline().append(Component.text(
                    line, region.enabled() ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY));
        }
        sender.sendMessage(message);
        return 1;
    }

    private int executeTeleport(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("/protectarea tp can only be run in-game.", NamedTextColor.RED));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        Optional<AreaRegion> existing = service.find(name);
        if (existing.isEmpty()) {
            notFound(sender, name);
            return 0;
        }
        AreaRecord record = existing.get().record();
        org.bukkit.World world = Bukkit.getWorld(record.world());
        if (world == null) {
            sender.sendMessage(Component.text("'" + name + "'s world isn't currently loaded.", NamedTextColor.RED));
            return 0;
        }
        double centerX = (record.minX() + record.maxX() + 1) / 2.0;
        double centerY = record.maxY() + 1;
        double centerZ = (record.minZ() + record.maxZ() + 1) / 2.0;
        player.teleport(new org.bukkit.Location(world, centerX, centerY, centerZ));
        sender.sendMessage(Component.text("Teleported to '" + name + "'.", NamedTextColor.GREEN));
        return 1;
    }

    // ---- shared helpers -----------------------------------------------------------------------

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> nameArg() {
        return Commands.argument("name", StringArgumentType.word());
    }

    private void notFound(CommandSender sender, String name) {
        sender.sendMessage(Component.text("No protected region found named '" + name + "'.", NamedTextColor.RED));
    }

    private void logAndNotify(CommandSender sender, String command, SQLException e) {
        plugin.getLogger().log(Level.WARNING, "Failed to process " + command, e);
        sync(() -> sender.sendMessage(Component.text("An internal error occurred.", NamedTextColor.RED)));
    }

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private static UUID actorUuid(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }
}
