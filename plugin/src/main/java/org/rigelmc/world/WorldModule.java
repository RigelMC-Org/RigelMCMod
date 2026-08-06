package org.rigelmc.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.command.PlayerSuggestions;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * World-management module. Ships with the flatlands sandbox (wipe + teleport) and the
 * admin-only world (with a TFM-style guest system) in this pass; a general per-region
 * protection system lives in {@code protect/} instead (see {@code CommandAccessRegistry}
 * for command-level protection, and the area-protection feature for block-level).
 */
public final class WorldModule implements PluginModule {

    private final FlatlandsService flatlandsService;
    private final AdminWorldService adminWorldService;
    private final SpawnService spawnService;
    private final PermissionGate permissionGate;
    private RigelMCMod plugin;

    public WorldModule(
            @NotNull FlatlandsService flatlandsService,
            @NotNull AdminWorldService adminWorldService,
            @NotNull SpawnService spawnService,
            @NotNull PermissionGate permissionGate) {
        this.flatlandsService = flatlandsService;
        this.adminWorldService = adminWorldService;
        this.spawnService = spawnService;
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "world";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        flatlandsService.initializeWorld();
        flatlandsService.scheduleAutowipeCycle();
        adminWorldService.ensureWorldExists();
        spawnService.loadPersistedState();
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new AdminWorldTeleportGuard(adminWorldService), plugin);
        AdminWorldPresenceGuard presenceGuard = new AdminWorldPresenceGuard(adminWorldService);
        plugin.getServer().getPluginManager().registerEvents(presenceGuard, plugin);
        presenceGuard.start(plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new SpawnJoinListener(plugin, spawnService), plugin);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(wipeFlatlandsCommand(), "Immediately wipe and regenerate the flatlands world");
        registrar.register(flatlandsCommand(), "Teleport to the flatlands sandbox world");
        registrar.register(adminWorldCommand(), "Go to the admin-only world, or manage its guest list", List.of("aw"));
        registrar.register(setSpawnCommand(), "Set the server spawn to your current location - Senior Admin+");
        registrar.register(spawnCommand(), "Teleport to the server spawn, or send another player there - Moderator+");
    }

    // ---- /setspawn ------------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> setSpawnCommand() {
        return Commands.literal("setspawn")
                .requires(source -> hasRank(source, "senior_admin"))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (!(sender instanceof Player player)) {
                        return CommandUsage.show(sender, "/setspawn (in-game only)");
                    }
                    spawnService.setSpawn(player.getLocation(), player.getUniqueId());
                    sender.sendMessage(Component.text("Server spawn set to your current location.", NamedTextColor.GOLD));
                    return 1;
                })
                .build();
    }

    // ---- /spawn [player] --------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> spawnCommand() {
        return Commands.literal("spawn")
                .requires(source -> source.getSender() instanceof Player)
                .executes(this::executeSpawnSelf)
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                        .requires(source -> hasRank(source, "moderator"))
                        .executes(this::executeSpawnOther))
                .build();
    }

    private int executeSpawnSelf(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        java.util.Optional<org.bukkit.Location> spawn = spawnService.spawnLocation();
        if (spawn.isEmpty()) {
            player.sendMessage(Component.text(
                    "The server spawn hasn't been set yet - ask a Senior Admin to run /setspawn.", NamedTextColor.RED));
            return 0;
        }
        player.teleport(spawn.get());
        player.sendMessage(Component.text("Teleported to spawn.", NamedTextColor.GRAY));
        return 1;
    }

    private int executeSpawnOther(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(Component.text("No online player found matching '" + targetName + "'.", NamedTextColor.RED));
            return 0;
        }
        java.util.Optional<org.bukkit.Location> spawn = spawnService.spawnLocation();
        if (spawn.isEmpty()) {
            sender.sendMessage(Component.text(
                    "The server spawn hasn't been set yet - ask a Senior Admin to run /setspawn.", NamedTextColor.RED));
            return 0;
        }
        target.teleport(spawn.get());
        sender.sendMessage(Component.text("Sent " + target.getName() + " to spawn.", NamedTextColor.GRAY));
        if (!target.equals(sender)) {
            target.sendMessage(Component.text("You've been sent to spawn by " + sender.getName() + ".", NamedTextColor.GRAY));
        }
        return 1;
    }

    private LiteralCommandNode<CommandSourceStack> wipeFlatlandsCommand() {
        return Commands.literal("wipeflatlands")
                .requires(source -> !(source.getSender() instanceof Player player)
                        || permissionGate.hasAtLeastCached(player.getUniqueId(), "senior_admin"))
                .executes(ctx -> {
                    ctx.getSource()
                            .getSender()
                            .sendMessage(Component.text("Wiping the flatlands world now...", NamedTextColor.GOLD));
                    flatlandsService.wipeNow(false);
                    return 1;
                })
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> flatlandsCommand() {
        return Commands.literal("flatlands")
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> {
                    Player player = (Player) ctx.getSource().getSender();
                    World world = Bukkit.getWorld(plugin.rigelConfig().flatlandsWorldName());
                    if (world == null) {
                        player.sendMessage(Component.text("The flatlands world isn't ready yet.", NamedTextColor.RED));
                        return 0;
                    }
                    player.teleport(world.getSpawnLocation());
                    player.sendMessage(Component.text("Teleported to the flatlands world.", NamedTextColor.GREEN));
                    return 1;
                })
                .build();
    }

    // ---- /adminworld (alias /aw) --------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> adminWorldCommand() {
        return Commands.literal("adminworld")
                .requires(source -> source.getSender() instanceof Player player
                        && adminWorldService.canAccess(player.getUniqueId()))
                .executes(this::executeGoToAdminWorld)
                .then(Commands.literal("guest")
                        .executes(ctx -> CommandUsage.show(
                                ctx.getSource().getSender(), "/adminworld guest <add|remove|list|purge> [player]"))
                        .then(Commands.literal("add")
                                .requires(source -> hasRank(source, "senior_admin"))
                                .executes(ctx -> CommandUsage.show(
                                        ctx.getSource().getSender(), "/adminworld guest add <player>"))
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                        .executes(this::executeGuestAdd)))
                        .then(Commands.literal("remove")
                                .requires(source -> hasRank(source, "senior_admin"))
                                .executes(ctx -> CommandUsage.show(
                                        ctx.getSource().getSender(), "/adminworld guest remove <player>"))
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PlayerSuggestions.ONLINE_PLAYERS)
                                        .executes(this::executeGuestRemove)))
                        .then(Commands.literal("list")
                                .requires(source -> hasRank(source, "moderator"))
                                .executes(this::executeGuestList))
                        .then(Commands.literal("purge")
                                .requires(source -> hasRank(source, "senior_admin"))
                                .executes(this::executeGuestPurge)))
                .build();
    }

    private int executeGoToAdminWorld(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        java.util.Optional<World> adminWorld = adminWorldService.world();
        if (adminWorld.isEmpty()) {
            player.sendMessage(Component.text("The admin world isn't ready yet.", NamedTextColor.RED));
            return 0;
        }
        if (player.getWorld().equals(adminWorld.get())) {
            World mainWorld = Bukkit.getWorlds().get(0);
            player.teleport(mainWorld.getSpawnLocation());
            player.sendMessage(Component.text("Going to the main world.", NamedTextColor.GRAY));
        } else {
            player.teleport(adminWorld.get().getSpawnLocation());
            player.sendMessage(Component.text("Going to the admin world.", NamedTextColor.GRAY));
        }
        return 1;
    }

    private int executeGuestAdd(CommandContext<CommandSourceStack> ctx) {
        Player sender = (Player) ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(Component.text("No online player found matching '" + targetName + "'.", NamedTextColor.RED));
            return 0;
        }
        if (adminWorldService.addGuest(target.getUniqueId(), sender.getUniqueId())) {
            sender.sendMessage(Component.text("Added " + target.getName() + " as an admin world guest.", NamedTextColor.AQUA));
            target.sendMessage(Component.text(
                    "You've been granted admin world access by " + sender.getName()
                            + " - it lasts only while they're online and still staff.",
                    NamedTextColor.AQUA));
        } else {
            sender.sendMessage(Component.text("Could not add that player to the guest list.", NamedTextColor.RED));
        }
        return 1;
    }

    private int executeGuestRemove(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        UUID targetUuid = target != null ? target.getUniqueId() : null;
        if (targetUuid == null) {
            sender.sendMessage(Component.text("No online player found matching '" + targetName + "'.", NamedTextColor.RED));
            return 0;
        }
        if (adminWorldService.removeGuest(targetUuid)) {
            sender.sendMessage(Component.text("Removed " + targetName + " from the admin world guest list.", NamedTextColor.AQUA));
        } else {
            sender.sendMessage(Component.text(targetName + " isn't an admin world guest.", NamedTextColor.YELLOW));
        }
        return 1;
    }

    private int executeGuestList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Map<UUID, UUID> guests = adminWorldService.guestSupervisors();
        if (guests.isEmpty()) {
            sender.sendMessage(Component.text("There are no admin world guests.", NamedTextColor.GRAY));
            return 1;
        }
        StringBuilder sb = new StringBuilder("Admin world guests: ");
        boolean first = true;
        for (Map.Entry<UUID, UUID> entry : guests.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            String guestName = nameOf(entry.getKey());
            String supervisorName = nameOf(entry.getValue());
            sb.append(guestName).append(" (supervisor: ").append(supervisorName).append(')');
        }
        sender.sendMessage(Component.text(sb.toString(), NamedTextColor.AQUA));
        return 1;
    }

    private int executeGuestPurge(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        adminWorldService.purgeGuests();
        sender.sendMessage(Component.text("Admin world guest list purged.", NamedTextColor.AQUA));
        return 1;
    }

    private static String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        return online != null ? online.getName() : uuid.toString();
    }

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }
}
