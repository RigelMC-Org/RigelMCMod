package org.rigelmc.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.command.CommandUsage;
import org.rigelmc.command.PlayerSuggestions;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * World-management module. Ships with the flatlands sandbox (wipe + teleport), the
 * admin-only world (with a TFM-style guest system), spawn get/set, and - user-requested -
 * a console/RCON-only wipe-and-regenerate for the primary world itself (see {@link
 * RegularWorldWipeService}); a general per-region protection system lives in {@code
 * protect/} instead (see {@code CommandAccessRegistry} for command-level protection, and
 * the area-protection feature for block-level).
 */
public final class WorldModule implements PluginModule {

    private final FlatlandsService flatlandsService;
    private final AdminWorldService adminWorldService;
    private final SpawnService spawnService;
    private final RegularWorldWipeService regularWorldWipeService;
    private final PermissionGate permissionGate;
    private RigelMCMod plugin;

    public WorldModule(
            @NotNull FlatlandsService flatlandsService,
            @NotNull AdminWorldService adminWorldService,
            @NotNull SpawnService spawnService,
            @NotNull RegularWorldWipeService regularWorldWipeService,
            @NotNull PermissionGate permissionGate) {
        this.flatlandsService = flatlandsService;
        this.adminWorldService = adminWorldService;
        this.spawnService = spawnService;
        this.regularWorldWipeService = regularWorldWipeService;
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
                .registerEvents(new SpawnJoinListener(plugin, spawnService, flatlandsService), plugin);
        MainWorldLockdownGuard mainWorldLockdownGuard =
                new MainWorldLockdownGuard(flatlandsService, permissionGate);
        plugin.getServer().getPluginManager().registerEvents(mainWorldLockdownGuard, plugin);
        mainWorldLockdownGuard.start(plugin);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(wipeFlatlandsCommand(), "Immediately wipe and regenerate the flatlands world");
        registrar.register(flatlandsCommand(), "Teleport to the flatlands sandbox world");
        registrar.register(wipeWorldCommand(), "Console/RCON only - wipe and regenerate the primary world");
        registrar.register(adminWorldCommand(), "Go to the admin-only world, or manage its guest list", List.of("aw"));
        registrar.register(setSpawnCommand(), "Set the server spawn to your current location - Senior Admin+");
        registrar.register(spawnCommand(), "Teleport to the server spawn, or send another player there - Moderator+");
        registrar.register(worldCommand(), "Teleport to the Nether or the End");
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

    /**
     * Gating depends on {@link RigelConfig#flatlandsWipeRequiresRestart} - read fresh on
     * every invocation, not cached, so a {@code /rmcm reload} takes effect immediately.
     *
     * <p>When {@code true}: console/RCON-only, matching TFM's own real {@code
     * Command_wipeflatlands} exactly ({@code SourceType.ONLY_CONSOLE}) - deliberate, not
     * an oversight, since this mode shuts the whole server down as part of the wipe (see
     * {@code FlatlandsService}'s javadoc), and an in-game sender would immediately kick
     * themselves with little warning.</p>
     *
     * <p>When {@code false} (the default - see {@code RigelConfig}'s javadoc for why):
     * no shutdown happens at all, so there's no reason to force console/RCON - usable
     * in-game too, gated to Senior Admin+ (matching TFM's own permission tier for this
     * command) same as every other Senior-Admin-only command in this class.</p>
     *
     * <p>Deliberately no top-level {@code .requires()} gate for either check - a failed
     * {@code .requires()} hides the whole node from Brigadier's parser, surfacing to a
     * player as a confusing raw "Unknown or incomplete command" instead of a clear
     * rejection message (the same class of issue {@code rmcm.RmcmModule}/{@code
     * rank.RankAdminModule}'s own javadoc already documents and fixes the same way).
     * Both checks happen inside the execute body instead.</p>
     */
    private LiteralCommandNode<CommandSourceStack> wipeFlatlandsCommand() {
        return Commands.literal("wipeflatlands")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    boolean requiresRestart = plugin.rigelConfig().flatlandsWipeRequiresRestart();
                    if (requiresRestart && sender instanceof Player) {
                        sender.sendMessage(Component.text(
                                "/wipeflatlands can only be run from the server console or RCON while"
                                        + " world.flatlands.wipe-requires-restart is enabled - it shuts the"
                                        + " whole server down as part of the wipe.",
                                NamedTextColor.RED));
                        return 0;
                    }
                    if (!requiresRestart && !hasRank(ctx.getSource(), "senior_admin")) {
                        sender.sendMessage(Component.text(
                                "You don't have permission to use /wipeflatlands.", NamedTextColor.RED));
                        return 0;
                    }
                    sender.sendMessage(Component.text("Wiping the flatlands world now...", NamedTextColor.GOLD));
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
                    if (flatlandsService.isWipeInProgress()) {
                        player.sendMessage(Component.text(
                                "The flatlands world is currently being wiped - try again in a few seconds.",
                                NamedTextColor.YELLOW));
                        return 0;
                    }
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

    /**
     * User-requested: "allow wiping and regeneration of the regular world." Deliberately
     * the most locked-down command in this whole class - see {@link
     * RegularWorldWipeService}'s javadoc for the full reasoning. Two gates, both enforced
     * inside the execute body rather than via {@code .requires()} (same reasoning as
     * {@link #wipeFlatlandsCommand()} above - a failed {@code .requires()} just shows
     * "Unknown or incomplete command" instead of a clear rejection):
     *
     * <ol>
     *   <li>Console/RCON only, unconditionally - no config toggle, unlike {@code
     *       /wipeflatlands}.</li>
     *   <li>Two-step confirmation - bare {@code /wipeworld} only explains what it does and
     *       does nothing destructive; {@code /wipeworld confirm} is the only way to
     *       actually trigger it.</li>
     * </ol>
     */
    private LiteralCommandNode<CommandSourceStack> wipeWorldCommand() {
        return Commands.literal("wipeworld")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    sender.sendMessage(Component.text(
                            "This permanently deletes and regenerates the ENTIRE primary world - every player's"
                                    + " builds in it will be lost. Run /wipeworld confirm to proceed.",
                            NamedTextColor.RED));
                    return 1;
                })
                .then(Commands.literal("confirm").executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (sender instanceof Player) {
                        sender.sendMessage(Component.text(
                                "/wipeworld can only be run from the server console or RCON.", NamedTextColor.RED));
                        return 0;
                    }
                    return regularWorldWipeService.wipeNow(sender) ? 1 : 0;
                }))
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

    // ---- /world <nether|end> -------------------------------------------------------------

    /**
     * User-requested: fast-travel to the Nether or the End without needing a portal - open
     * to everyone (no rank gate), matching {@code /flatlands}'s own "player only" requirement.
     */
    private LiteralCommandNode<CommandSourceStack> worldCommand() {
        return Commands.literal("world")
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> CommandUsage.show(ctx.getSource().getSender(), "/world <end|nether>"))
                .then(Commands.literal("nether")
                        .executes(ctx -> executeGoToDimension(ctx, World.Environment.NETHER, "Nether")))
                .then(Commands.literal("end")
                        .executes(ctx -> executeGoToDimension(ctx, World.Environment.THE_END, "End")))
                .build();
    }

    private int executeGoToDimension(CommandContext<CommandSourceStack> ctx, World.Environment environment, String label) {
        Player player = (Player) ctx.getSource().getSender();
        Optional<World> target = Bukkit.getWorlds().stream()
                .filter(world -> world.getEnvironment() == environment)
                .findFirst();
        if (target.isEmpty()) {
            player.sendMessage(Component.text("The " + label + " isn't available on this server.", NamedTextColor.RED));
            return 0;
        }
        World world = target.get();
        Location destination = findSafeLocation(world, world.getSpawnLocation());
        if (destination == null) {
            player.sendMessage(Component.text(
                    "Couldn't find a clearly safe spot near the " + label + "'s spawn - sending you there anyway,"
                            + " watch your step.",
                    NamedTextColor.YELLOW));
            destination = world.getSpawnLocation();
        }
        player.teleport(destination);
        player.sendMessage(Component.text("Teleported to the " + label + ".", NamedTextColor.GREEN));
        return 1;
    }

    /** How far up/down from the starting Y to scan for a safe spot, in blocks. */
    private static final int SAFE_LOCATION_VERTICAL_RANGE = 48;

    /** How many rings of neighboring columns to try if the starting column has nothing safe. */
    private static final int SAFE_LOCATION_COLUMN_RADIUS = 4;

    /**
     * User-requested (a bounded safe-spot scan, not a raw {@code World#getSpawnLocation()}
     * call): the Nether has no equivalent of the End's small, always-safe obsidian spawn
     * platform - its own {@code getSpawnLocation()} can land squarely inside solid netherrack
     * or a lava sea. No existing "safe teleport" utility exists anywhere else in this
     * codebase to reuse (every other teleport in this project either reuses a
     * previously-stored {@code Location} or a custom-flat-world's own known-safe ground) -
     * this is a fresh, narrowly-scoped one, not meant as a general-purpose utility.
     *
     * <p>Checks {@code start}'s own column first - the End's real spawn passes immediately
     * there, so this is effectively free for that dimension - then scans vertically at that
     * column, then expands outward to nearby columns. Bounded throughout by {@link
     * #SAFE_LOCATION_VERTICAL_RANGE}/{@link #SAFE_LOCATION_COLUMN_RADIUS}, never a full-world
     * search.</p>
     *
     * @return a safe location, or {@code null} if nothing was found within bounds (caller
     *     falls back to {@code start} anyway with a warning, rather than refusing outright)
     */
    @Nullable
    private static Location findSafeLocation(@NotNull World world, @NotNull Location start) {
        int startX = start.getBlockX();
        int startZ = start.getBlockZ();
        for (int ring = 0; ring <= SAFE_LOCATION_COLUMN_RADIUS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue; // only this ring's outer edge - closer rings already checked
                    }
                    Location found = scanColumn(world, startX + dx, startZ + dz, start.getBlockY());
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static Location scanColumn(@NotNull World world, int x, int z, int centerY) {
        int minY = Math.max(world.getMinHeight(), centerY - SAFE_LOCATION_VERTICAL_RANGE);
        int maxY = Math.min(world.getMaxHeight() - 1, centerY + SAFE_LOCATION_VERTICAL_RANGE);
        for (int y = minY; y <= maxY; y++) {
            if (isSafeStandingSpot(world, x, y, z)) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    /** Two non-liquid, passable blocks (feet + head) over a solid, non-liquid floor. */
    private static boolean isSafeStandingSpot(@NotNull World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block floor = world.getBlockAt(x, y - 1, z);
        return feet.isPassable() && !feet.isLiquid()
                && head.isPassable() && !head.isLiquid()
                && floor.isSolid() && !floor.isLiquid();
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
