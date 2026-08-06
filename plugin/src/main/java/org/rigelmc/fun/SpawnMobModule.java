package org.rigelmc.fun;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * {@code /spawnmob <type> [amount]} - a safe, NBT-free alternative to vanilla {@code
 * /summon} for Moderator+. Requested directly as "a secure way to spawn mobs (for ops)":
 * on this Free-OP server vanilla {@code /summon} is otherwise reachable by every
 * auto-op'd player and accepts an arbitrary raw NBT argument - the same crash-exploit
 * surface {@code protect.crash}'s item/entity guards exist to close elsewhere (malformed
 * or oversized NBT, cursed component data, ...) - so it's now blocked below Senior Admin
 * via {@code protect.command-access} instead. This command closes the resulting gap for
 * everyone else with a version that has no NBT argument to abuse at all: only a
 * whitelisted {@link EntityType} and a capped headcount ever reach {@link
 * org.bukkit.World#spawn}.
 *
 * <p>Only entity types that are both {@link EntityType#isSpawnable()} and {@link
 * EntityType#isAlive()} are accepted - i.e. an actual living mob (zombies, cows,
 * villagers, ...), not a projectile, vehicle, display entity, area-effect cloud, or
 * anything else vanilla's own {@code /summon} could otherwise be used to abuse. No TFM
 * equivalent exists to port - this is RigelMCMod-original.</p>
 */
public final class SpawnMobModule implements PluginModule {

    /** Suggests every spawnable living mob type's key name, filtered by what's typed so far. */
    private static final SuggestionProvider<CommandSourceStack> MOB_TYPES = (ctx, builder) -> {
        String remaining = builder.getRemainingLowerCase();
        for (EntityType type : EntityType.values()) {
            if (!isSpawnableMob(type)) {
                continue;
            }
            String key = type.name().toLowerCase(Locale.ROOT);
            if (key.startsWith(remaining)) {
                builder.suggest(key);
            }
        }
        return builder.buildFuture();
    };

    private final PermissionGate permissionGate;
    private RigelMCMod plugin;

    public SpawnMobModule(@NotNull PermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "spawnmob";
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
        registrar.register(spawnMobCommand(), "Spawn a capped number of a chosen mob type - Moderator+");
    }

    private LiteralCommandNode<CommandSourceStack> spawnMobCommand() {
        return Commands.literal("spawnmob")
                .requires(source -> hasRank(source, "moderator"))
                .executes(ctx -> org.rigelmc.command.CommandUsage.show(
                        ctx.getSource().getSender(), "/spawnmob <type> [amount]"))
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests(MOB_TYPES)
                        .executes(ctx -> executeSpawn(ctx, 1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeSpawn(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))
                .build();
    }

    private int executeSpawn(CommandContext<CommandSourceStack> ctx, int requestedAmount) {
        var sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("/spawnmob can only be run in-game.", NamedTextColor.RED));
            return 0;
        }

        String rawType = StringArgumentType.getString(ctx, "type");
        EntityType type = parseMobType(rawType);
        if (type == null) {
            sender.sendMessage(Component.text(
                    "'" + rawType + "' isn't a spawnable mob type. Tab-complete the <type> argument to see valid"
                            + " options.",
                    NamedTextColor.RED));
            return 0;
        }

        int max = plugin.rigelConfig().spawnMobMaxAmount();
        if (requestedAmount > max) {
            sender.sendMessage(Component.text(
                    "You can only spawn up to " + max + " mob(s) at once (asked for " + requestedAmount + ").",
                    NamedTextColor.RED));
            return 0;
        }

        @SuppressWarnings("unchecked") // guarded by isSpawnableMob's isAlive() check in parseMobType
        Class<? extends LivingEntity> livingClass = (Class<? extends LivingEntity>) type.getEntityClass();
        Location origin = player.getLocation();
        int spawned = 0;
        for (int i = 0; i < requestedAmount; i++) {
            // Small random horizontal jitter so a multi-mob spawn doesn't cram every
            // mob into the exact same block (avoids instant suffocation-crowding) -
            // vertical stays fixed so nothing spawns inside the floor/ceiling.
            Location at = origin.clone().add(
                    (Math.random() - 0.5) * 3.0, 0, (Math.random() - 0.5) * 3.0);
            LivingEntity spawnedEntity =
                    origin.getWorld().spawn(at, livingClass, CreatureSpawnEvent.SpawnReason.COMMAND, false, null);
            if (spawnedEntity != null) {
                spawned++;
            }
        }

        sender.sendMessage(Component.text(
                "Spawned " + spawned + " " + type.name().toLowerCase(Locale.ROOT) + "(s).", NamedTextColor.GREEN));
        plugin.getLogger().info(
                sender.getName() + " used /spawnmob to spawn " + spawned + " " + type.name() + " at "
                        + formatLocation(origin));
        return 1;
    }

    private static EntityType parseMobType(String raw) {
        EntityType type;
        try {
            type = EntityType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return isSpawnableMob(type) ? type : null;
    }

    /** @return whether {@code type} is an actual spawnable living mob - see this class's javadoc. */
    private static boolean isSpawnableMob(EntityType type) {
        return type.isSpawnable() && type.isAlive() && LivingEntity.class.isAssignableFrom(type.getEntityClass());
    }

    private static String formatLocation(Location location) {
        return "(" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ") in "
                + location.getWorld().getName();
    }

    private boolean hasRank(CommandSourceStack source, String rankId) {
        if (source.getSender() instanceof Player player) {
            return permissionGate.hasAtLeastCached(player.getUniqueId(), rankId);
        }
        return true; // console always allowed
    }
}
