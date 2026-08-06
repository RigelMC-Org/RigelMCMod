package org.rigelmc.world;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;

/**
 * The server's single configured spawn point, set via {@code /setspawn} - TFM-style
 * ({@code SpawnManager}, {@code Command_setspawn}, {@code Command_spawn}), studied
 * directly from its real source before porting.
 *
 * <p>Unlike TFM, the location itself is <b>not</b> stored in {@code config.yml}: this
 * codebase's own convention is that {@code config.yml} is operator-authored, additive-merge
 * state ({@link org.rigelmc.core.ConfigUpdater}), while state that's <i>mutated at
 * runtime by an admin command</i> belongs in the database instead - see e.g. {@code
 * punish.cage.CageService} for the exact same split. Only the join/respawn *behavior
 * toggles* that consume this location ({@code world.spawn.send-on-join}/{@code
 * send-on-respawn}) live in config - see {@code RigelConfig#spawnSendOnJoin}.</p>
 */
public final class SpawnService {

    private final RigelMCMod plugin;
    private final SpawnDao spawnDao;
    private volatile SpawnRecord current;

    public SpawnService(@NotNull RigelMCMod plugin, @NotNull SpawnDao spawnDao) {
        this.plugin = plugin;
        this.spawnDao = spawnDao;
    }

    /** Loads the persisted spawn point, if {@code /setspawn} has ever been run. Call once on enable. */
    public void loadPersistedState() {
        try {
            current = spawnDao.find().orElse(null);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load persisted spawn location", e);
        }
    }

    /** @return whether {@code /setspawn} has ever been run. */
    public boolean isSet() {
        return current != null;
    }

    /**
     * @return the configured spawn as a live {@link Location}, or empty if {@code
     *     /setspawn} has never been run, or if its world isn't currently loaded (e.g. a
     *     since-deleted/renamed world) - logged as a warning rather than silently
     *     teleporting into the wrong world.
     */
    @NotNull
    public Optional<Location> spawnLocation() {
        SpawnRecord record = current;
        if (record == null) {
            return Optional.empty();
        }
        World world = Bukkit.getWorld(record.world());
        if (world == null) {
            plugin.getLogger()
                    .warning("Configured spawn world '" + record.world()
                            + "' isn't currently loaded - /spawn and spawn-on-join won't work until it is,"
                            + " or until /setspawn is run again.");
            return Optional.empty();
        }
        return Optional.of(new Location(world, record.x(), record.y(), record.z(), record.yaw(), record.pitch()));
    }

    /** Sets the server spawn to {@code location}, persisting it so it survives a restart. */
    public void setSpawn(@NotNull Location location, @Nullable UUID setBy) {
        World world = location.getWorld();
        SpawnRecord record = new SpawnRecord(
                world != null ? world.getName() : "world",
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(),
                setBy, System.currentTimeMillis());
        current = record;
        try {
            spawnDao.upsert(record);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to persist new spawn location", e);
        }
    }
}
