package org.rigelmc.world;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.dao.WorldStateDao;

/**
 * Manages the disposable "flatlands" sandbox world: creating it on first enable,
 * wiping it on demand ({@code /wipeflatlands}) or automatically on a configurable
 * interval - see docs/architecture.md "world/" module. Deliberately reuses vanilla's
 * own superflat generator ({@link WorldType#FLAT}) rather than a custom
 * {@code ChunkGenerator}, consistent with this project's "reuse a proven tool instead
 * of reinventing it" pattern elsewhere (CoreProtect, LibsDisguises, ...).
 *
 * <p><b>Restart-based wipe</b> (the default - {@link RigelConfig#flatlandsWipeRestartsServer}):
 * deleting a world folder from disk while the server process is still running has no
 * OS-level guarantee that every file handle Bukkit/the JVM ever opened for it has actually
 * been released, even after {@link Bukkit#unloadWorld}, on every platform/filesystem - a
 * user-reported real-world reliability concern, not a hypothetical one. Instead of deleting
 * in-place, a restart-based wipe marks the wipe <b>pending</b> in {@link #worldStateDao}
 * (survives the restart - it's a separate file from the world folder itself, never touched
 * by the wipe), restarts the whole server via {@link Bukkit#getServer()}{@code .restart()},
 * and only actually deletes the folder on the fresh boot's {@link #initializeWorld()} call -
 * <i>before</i> any world is loaded at all in the new process, which is the only point a
 * stale file handle from the old process is fully guaranteed to be irrelevant (the old
 * process has, by definition, completely exited by the time a new one is running). The
 * lower-disruption in-place path (delete-while-running, only flatlands-world players
 * affected) is still available by setting that config key to {@code false}.</p>
 *
 * <p><b>Unverified against a live server</b> in this session - world creation/deletion
 * needs a real Bukkit world container this test environment doesn't have. The pure
 * scheduling decision ({@link #isWipeDue}) is unit-tested; the actual file/world I/O is
 * not.</p>
 */
public final class FlatlandsService {

    private final RigelMCMod plugin;
    private final WorldStateDao worldStateDao;
    private final ExecutorService dbExecutor;

    public FlatlandsService(
            @NotNull RigelMCMod plugin, @NotNull WorldStateDao worldStateDao, @NotNull ExecutorService dbExecutor) {
        this.plugin = plugin;
        this.worldStateDao = worldStateDao;
        this.dbExecutor = dbExecutor;
    }

    /**
     * Call once on enable, in place of a bare {@link #ensureWorldExists()} - first checks
     * (and clears) a pending restart-based wipe flag, deleting the world folder before it
     * ever gets loaded if one is set, <i>then</i> ensures the world exists. Safe to call
     * even when a restart-based wipe was never used at all (the common "no row yet" case
     * just skips straight to {@link #ensureWorldExists()}).
     */
    public void initializeWorld() {
        String name = plugin.rigelConfig().flatlandsWorldName();
        dbExecutor.submit(() -> {
            boolean pending;
            try {
                pending = worldStateDao.isPendingWipe(name);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to read pending flatlands wipe flag on boot", e);
                pending = false;
            }
            if (pending) {
                deleteWorldFolderRecursively(new File(Bukkit.getWorldContainer(), name));
                try {
                    worldStateDao.setPendingWipe(name, false);
                    worldStateDao.setLastWipeAt(name, System.currentTimeMillis());
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to clear pending flatlands wipe flag after boot-time delete", e);
                }
                plugin.getLogger().info("Completed a pending restart-based flatlands wipe on boot.");
            }
            Bukkit.getScheduler().runTask(plugin, this::ensureWorldExists);
        });
    }

    /** Creates the flatlands world if it doesn't already exist. Main-thread only. */
    public void ensureWorldExists() {
        String name = plugin.rigelConfig().flatlandsWorldName();
        if (Bukkit.getWorld(name) != null) {
            return;
        }
        createWorld(name);
        plugin.getLogger().info("Created flatlands world '" + name + "'.");
    }

    /**
     * Wipes the flatlands world right now: evacuates any players in it, unloads it,
     * deletes it from disk, and recreates it fresh. Safe to call whether or not the
     * world currently exists.
     *
     * @param warnFirst if {@code true}, broadcasts a warning and waits
     *     {@code world.flatlands.autowipe.warning-minutes-before} before actually
     *     wiping (used by the automatic schedule); manual {@code /wipeflatlands} passes
     *     {@code false} for an immediate wipe.
     */
    public void wipeNow(boolean warnFirst) {
        RigelConfig config = plugin.rigelConfig();
        String name = config.flatlandsWorldName();

        if (warnFirst) {
            Duration warning = config.flatlandsAutowipeWarning();
            Bukkit.broadcast(Component.text(
                    "The flatlands world will be wiped in " + warning.toMinutes() + " minute(s)!",
                    NamedTextColor.GOLD));
            Bukkit.getScheduler()
                    .runTaskLater(plugin, () -> performWipe(name), ticksFrom(warning));
        } else {
            performWipe(name);
        }
    }

    /** Must run on the main thread. */
    private void performWipe(String name) {
        if (plugin.rigelConfig().flatlandsWipeRestartsServer()) {
            performRestartWipe(name);
            return;
        }

        World world = Bukkit.getWorld(name);
        if (world != null) {
            World safeWorld = Bukkit.getWorlds().get(0);
            for (Player player : world.getPlayers()) {
                player.teleport(safeWorld.getSpawnLocation());
                player.sendMessage(Component.text(
                        "The flatlands world is being wiped - you've been moved to spawn.", NamedTextColor.YELLOW));
            }
            Bukkit.unloadWorld(world, false);
        }

        dbExecutor.submit(() -> {
            deleteWorldFolderRecursively(new File(Bukkit.getWorldContainer(), name));
            Bukkit.getScheduler().runTask(plugin, () -> {
                createWorld(name);
                Bukkit.broadcast(Component.text("The flatlands world has been wiped.", NamedTextColor.GREEN));
                recordWipeAndScheduleNext(name);
            });
        });
    }

    /**
     * Marks the wipe pending (so {@link #initializeWorld()} deletes the folder on the
     * fresh boot before anything else touches it - see this class's javadoc), then
     * restarts the whole server a few seconds later, giving the broadcast below a moment
     * to actually reach clients before they're disconnected.
     */
    private void performRestartWipe(String name) {
        Bukkit.broadcast(Component.text(
                "The server is restarting to wipe the flatlands world - you'll be reconnected shortly.",
                NamedTextColor.GOLD));
        dbExecutor.submit(() -> {
            try {
                worldStateDao.setPendingWipe(name, true);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to persist a pending flatlands wipe flag - restarting anyway, but the wipe"
                                + " itself won't happen without this flag set", e);
            }
            Bukkit.getScheduler().runTask(plugin,
                    () -> Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.getServer().restart(), 60L));
        });
    }

    private void createWorld(String name) {
        new WorldCreator(name).type(WorldType.FLAT).generateStructures(false).createWorld();
    }

    private void deleteWorldFolderRecursively(File worldFolder) {
        if (!worldFolder.exists()) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(worldFolder.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to delete " + path + " during flatlands wipe", e);
                }
            });
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to walk flatlands world folder for deletion", e);
        }
    }

    /** Persists the wipe timestamp, then schedules the next autowipe cycle if enabled. */
    private void recordWipeAndScheduleNext(String name) {
        dbExecutor.submit(() -> {
            try {
                worldStateDao.setLastWipeAt(name, System.currentTimeMillis());
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist flatlands last-wipe timestamp", e);
            }
            scheduleAutowipeCycle();
        });
    }

    /**
     * Reads the persisted last-wipe time (or treats "never wiped" as "wiped right now",
     * so a fresh install doesn't immediately wipe itself on first boot), computes the
     * next due time, and schedules the warning + wipe via delayed tasks rather than
     * polling. Safe to call from any thread - does its own DB read asynchronously.
     */
    public void scheduleAutowipeCycle() {
        RigelConfig config = plugin.rigelConfig();
        if (!config.flatlandsAutowipeEnabled()) {
            return;
        }
        String name = config.flatlandsWorldName();
        Duration interval = config.flatlandsAutowipeInterval();
        Duration warning = config.flatlandsAutowipeWarning();

        dbExecutor.submit(() -> {
            long now = System.currentTimeMillis();
            long lastWipeAt;
            try {
                lastWipeAt = worldStateDao.findLastWipeAt(name).orElse(now);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to read flatlands last-wipe timestamp", e);
                return;
            }

            long nextWipeAt = lastWipeAt + interval.toMillis();
            long delayUntilWipeMillis = Math.max(nextWipeAt - now, 0);
            long delayUntilWarningMillis = Math.max(delayUntilWipeMillis - warning.toMillis(), 0);

            Bukkit.getScheduler()
                    .runTaskLater(plugin, () -> wipeNow(true), ticksFrom(Duration.ofMillis(delayUntilWarningMillis)));
        });
    }

    /**
     * Pure decision logic, extracted for unit testing without a live Bukkit world: has
     * enough time passed since the last wipe for another one to be due?
     */
    public static boolean isWipeDue(long lastWipeAtEpochMillis, @NotNull Duration interval, long nowEpochMillis) {
        return nowEpochMillis - lastWipeAtEpochMillis >= interval.toMillis();
    }

    private static long ticksFrom(Duration duration) {
        return Math.max(duration.toMillis() / 50, 1); // 50ms per tick, minimum 1 tick
    }
}
