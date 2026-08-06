package org.rigelmc.protect.antigrief;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderSignal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * {@code /mobpurge} and {@code /entitywipe} - TFM-style entity cleanup, studied directly
 * from its real {@code Command_mobpurge}/{@code EntityWiper} source, kept as two separate
 * commands because server owners commonly want to clear one kind of clutter without
 * touching the other:
 *
 * <ul>
 *   <li>{@code /mobpurge [radius]} (alias {@code /mp}, matching TFM) - removes every
 *       {@link Mob} (every AI-controlled hostile/passive/neutral creature - the modern
 *       Paper API's unified equivalent of TFM's own {@code Creature || Ghast || Slime ||
 *       EnderDragon || Ambient} check, adapted rather than ported verbatim since that
 *       exact type list predates several current mob classes), excluding named/tamed/
 *       leashed mobs by default (config). Deliberately excludes {@link ArmorStand} -
 *       unlike a plain {@code LivingEntity} check, {@code Mob} doesn't extend to it, so a
 *       player's armor-stand builds survive a purge exactly like TFM's own definition.</li>
 *   <li>{@code /entitywipe [radius]} (aliases {@code /ew}, {@code /rd}, both matching
 *       TFM) - removes non-mob clutter: item drops, experience orbs, every kind of
 *       {@link Projectile} (arrows/tridents, snowballs, eggs, thrown potions/exp bottles,
 *       fireballs, fishing hooks, ...), falling blocks, fireworks, area-effect clouds,
 *       minecarts, boats, armor stands, end crystals, and ender-eye projectiles - matches
 *       TFM's own {@code EntityWiper.wipables} list (collapsed to the single {@code
 *       Projectile} supertype where TFM listed several of its subtypes individually).</li>
 * </ul>
 *
 * Both default to sweeping <b>every</b> loaded world (overworld, nether, end, flatlands,
 * admin world, ...) regardless of which world the sender is standing in or whether the
 * sender is a player or console - matches TFM's own real default exactly (see {@link
 * #worldsToSweep} for the one exception: an optional radius argument, which is inherently
 * relative to the sender's own location, restricts the sweep to that many blocks around a
 * player sender in their current world only). Both broadcast the result to every online
 * player, matching TFM's own admin-action
 * convention (see {@code /opall}'s server-wide broadcast). Both can also run automatically
 * on a configurable interval (0 = on-demand only). Reads {@code plugin.rigelConfig()}
 * fresh rather than caching it - see {@code AntiNukeGuard}'s javadoc for why.
 *
 * <p>Gated to Moderator+ here rather than TFM's Super Admin-only tier (its {@code
 * Rank.SUPER_ADMIN}) - a deliberate RigelMCMod divergence: this is routine anti-lag
 * upkeep, not a power capable of harming a player or the world, so it's left at the same
 * tier as this project's other routine cleanup/investigative utilities rather than
 * reserved for the top rank.</p>
 */
public final class EntityCleanupModule implements PluginModule {

    private final PermissionGate permissionGate;
    private RigelMCMod plugin;

    public EntityCleanupModule(@NotNull PermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "cleanup";
    }

    @Override
    public boolean isEnabled(RigelConfig cfg) {
        return cfg.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        this.plugin = plugin;
        scheduleAuto(plugin.rigelConfig().mobpurgeAutoIntervalMinutes(), () -> runMobpurge(null, null, false));
        scheduleAuto(plugin.rigelConfig().entitywipeAutoIntervalMinutes(), () -> runEntitywipe(null, null, false));
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(mobpurgeCommand(), "Remove all mobs", List.of("mp"));
        registrar.register(entitywipeCommand(),
                "Remove dropped items, projectiles, and other non-mob clutter", List.of("ew", "rd"));
    }

    private void scheduleAuto(int intervalMinutes, Runnable task) {
        if (intervalMinutes <= 0) {
            return;
        }
        long ticks = intervalMinutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, task, ticks, ticks);
    }

    // ---- /mobpurge ----------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> mobpurgeCommand() {
        return Commands.literal("mobpurge")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> {
                    runMobpurge(senderOf(ctx), null, true);
                    return 1;
                })
                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            runMobpurge(senderOf(ctx), IntegerArgumentType.getInteger(ctx, "radius"), true);
                            return 1;
                        }))
                .build();
    }

    private void runMobpurge(CommandSender sender, Integer radius, boolean announce) {
        RigelConfig config = plugin.rigelConfig();
        int removed = 0;
        for (World world : worldsToSweep(sender, radius)) {
            for (Entity entity : entitiesInScope(world, sender, radius)) {
                // Mob (not a bare LivingEntity check) - see this class's javadoc for why
                // this correctly excludes ArmorStand (a LivingEntity, but not a mob).
                if (!(entity instanceof Mob mob)) {
                    continue;
                }
                if (config.mobpurgeExcludeNamed() && mob.isCustomNameVisible() && mob.getCustomName() != null) {
                    continue;
                }
                if (config.mobpurgeExcludeTamed() && mob instanceof Tameable tameable && tameable.isTamed()) {
                    continue;
                }
                if (config.mobpurgeExcludeLeashed() && mob.isLeashed()) {
                    continue;
                }
                mob.remove();
                removed++;
            }
        }
        if (announce) {
            broadcast((sender != null ? sender.getName() : "The server") + " purged " + removed + " mob(s).");
        }
    }

    // ---- /entitywipe --------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> entitywipeCommand() {
        return Commands.literal("entitywipe")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> {
                    runEntitywipe(senderOf(ctx), null, true);
                    return 1;
                })
                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            runEntitywipe(senderOf(ctx), IntegerArgumentType.getInteger(ctx, "radius"), true);
                            return 1;
                        }))
                .build();
    }

    private void runEntitywipe(CommandSender sender, Integer radius, boolean announce) {
        int removed = 0;
        for (World world : worldsToSweep(sender, radius)) {
            for (Entity entity : entitiesInScope(world, sender, radius)) {
                if (isWipeable(entity)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (announce) {
            broadcast((sender != null ? sender.getName() : "The server") + " wiped " + removed + " dropped entity(ies).");
        }
    }

    /** @return whether {@code entity} is non-mob clutter {@code /entitywipe} should remove - see this class's javadoc. */
    private static boolean isWipeable(Entity entity) {
        return entity instanceof Item
                || entity instanceof ExperienceOrb
                || entity instanceof Projectile
                || entity instanceof FallingBlock
                || entity instanceof Firework
                || entity instanceof AreaEffectCloud
                || entity instanceof Minecart
                || entity instanceof Boat
                || entity instanceof ArmorStand
                || entity instanceof EnderCrystal
                || entity instanceof EnderSignal;
    }

    // ---- shared helpers ----------------------------------------------------------------

    /**
     * A bare (no-radius) {@code /mobpurge} or {@code /entitywipe} - whether run by a
     * player or console - sweeps <b>every</b> loaded world (overworld, nether, end,
     * flatlands, admin world, ...), matching TFM's own real behavior exactly (its
     * {@code Command_mobpurge}/{@code EntityWiper} both default to {@code
     * Bukkit.getWorlds()}, not the sender's current world - confirmed by reading its
     * source directly). Previously this only swept the sender's own world for a player,
     * which meant griefer-spawned clutter in any world the staff member wasn't currently
     * standing in went untouched - not TFM parity, and not the intended behavior.
     *
     * <p>A radius argument is the one case that stays scoped to a single world: "within
     * N blocks of me" is inherently relative to the sender's own location, so sweeping
     * every world for it wouldn't make sense - {@link #entitiesInScope} already requires
     * a {@link Player} sender for the same reason.</p>
     */
    private List<World> worldsToSweep(CommandSender sender, Integer radius) {
        if (radius != null && sender instanceof Player player) {
            return List.of(player.getWorld());
        }
        return Bukkit.getWorlds();
    }

    private List<Entity> entitiesInScope(World world, CommandSender sender, Integer radius) {
        if (radius == null || !(sender instanceof Player player)) {
            return new ArrayList<>(world.getEntities());
        }
        Location center = player.getLocation();
        List<Entity> result = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (entity.getLocation().distance(center) <= radius) {
                result.add(entity);
            }
        }
        return result;
    }

    private void broadcast(String message) {
        Component component = Component.text(message, NamedTextColor.AQUA);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(component);
        }
        plugin.getLogger().info(message);
    }

    private static CommandSender senderOf(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }
}
