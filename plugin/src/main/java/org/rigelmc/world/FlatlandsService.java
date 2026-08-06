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
 * interval - see docs/architecture.md "world/" module. Generates with the standalone
 * CleanroomGenerator plugin when it's installed (user-requested, for Eaglercraft
 * compatibility - see {@link CleanroomGeneratorBridge}'s javadoc), falling back to
 * vanilla's own superflat generator ({@link WorldType#FLAT}) otherwise - consistent with
 * this project's "reuse a proven tool instead of reinventing it" pattern elsewhere
 * (CoreProtect, LibsDisguises, ...).
 *
 * <p><b>Shutdown-based wipe</b> (the default - {@link RigelConfig#flatlandsWipeRequiresRestart}):
 * deleting a world folder from disk while the server process is still running has no
 * OS-level guarantee that every file handle Bukkit/the JVM ever opened for it has actually
 * been released, even after {@link Bukkit#unloadWorld}, on every platform/filesystem - a
 * user-reported real-world reliability concern, not a hypothetical one. Instead of deleting
 * in-place, this marks the wipe <b>pending</b> in {@link #worldStateDao} (survives the
 * process exiting - it's a separate file from the world folder itself, never touched by the
 * wipe) and calls {@link Bukkit#getServer()}{@code .shutdown()} - <i>not</i> {@code
 * .restart()}. TFM's own real {@code Command_wipeflatlands} (studied directly) makes the
 * exact same choice, and for a concrete reason: {@code Server#restart()} only actually
 * relaunches the process if the server was started through a wrapper/script that
 * specifically supports Paper's restart protocol - on any setup that doesn't (a bare
 * {@code java -jar paper.jar}, most container/panel setups without that exact wrapper
 * configured), {@code restart()} either does nothing more than a shutdown or silently never
 * comes back, so the pending flag's actual deletion step - which only runs at the very
 * start of the <i>next</i> boot - never gets a next boot to run in. This is a confirmed,
 * user-reported failure mode this project's own earlier {@code restart()}-based version
 * hit in practice, not a theoretical concern - {@code shutdown()} makes no such assumption:
 * whatever brings the process back up (a supervisor, a panel, or an operator typing the
 * start command again) will trigger {@link #initializeWorld()} on its own, at which point
 * the pending flag is still there and the actual deletion happens - <i>before</i> any world
 * is loaded at all in the new process, which is the only point a stale file handle from the
 * old process is fully guaranteed to be irrelevant (the old process has, by definition,
 * completely exited by the time a new one is running). The lower-disruption in-place path
 * (delete-while-running, only flatlands-world players affected, no shutdown at all) is
 * still available by setting that config key to {@code false}.</p>
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
    private final CleanroomGeneratorBridge cleanroomGeneratorBridge;

    public FlatlandsService(
            @NotNull RigelMCMod plugin, @NotNull WorldStateDao worldStateDao, @NotNull ExecutorService dbExecutor) {
        this(plugin, worldStateDao, dbExecutor, new CleanroomGeneratorBridge());
    }

    /** Test-only constructor - injects the generator bridge directly. */
    FlatlandsService(
            @NotNull RigelMCMod plugin, @NotNull WorldStateDao worldStateDao, @NotNull ExecutorService dbExecutor,
            @NotNull CleanroomGeneratorBridge cleanroomGeneratorBridge) {
        this.plugin = plugin;
        this.worldStateDao = worldStateDao;
        this.dbExecutor = dbExecutor;
        this.cleanroomGeneratorBridge = cleanroomGeneratorBridge;
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
        if (plugin.rigelConfig().flatlandsWipeRequiresRestart()) {
            performShutdownWipe(name);
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
     * next boot before anything else touches it - see this class's javadoc), then shuts
     * the whole server down - matching TFM's own real {@code Command_wipeflatlands}
     * exactly, including kicking every online player with a specific reason first rather
     * than just letting the shutdown disconnect them with a generic message.
     *
     * <p>Deliberately does <b>not</b> attempt to bring the server back up itself - see
     * this class's own javadoc for why {@code restart()} can't be trusted to actually do
     * that on every setup. Whatever the operator's own process supervisor/panel/manual
     * restart habit already is remains in charge of the next boot, same as TFM.</p>
     */
    private void performShutdownWipe(String name) {
        Component notice = Component.text(
                "The server is going offline for a flatlands wipe - come back in a few minutes.",
                NamedTextColor.GOLD);
        Bukkit.broadcast(notice);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.kick(notice);
        }

        dbExecutor.submit(() -> {
            try {
                worldStateDao.setPendingWipe(name, true);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to persist a pending flatlands wipe flag - shutting down anyway, but the wipe"
                                + " itself won't happen on the next boot without this flag set", e);
            }
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getServer().shutdown());
        });
    }

    /**
     * Uses the CleanroomGenerator plugin's own real generator when it's installed (user-
     * requested, for Eaglercraft compatibility - see {@link CleanroomGeneratorBridge}'s
     * javadoc), falling back to vanilla's own {@code WorldType.FLAT} otherwise. Explicitly
     * sets the spawn location afterward - matching TFM's own real {@code
     * world.Flatlands#generateWorld} exactly ({@code world.setSpawnLocation(0, 50, 0)},
     * {@code world.setSpawnFlags(false, false)}), which this project's own version never
     * did, leaving Paper's own auto-spawn-search to pick wherever it likes (a real,
     * user-reported symptom: players spawning in an empty void well below any generated
     * floor). {@code y=50} matches TFM's own hardcoded value for its own hardcoded default
     * layer heights, which {@link RigelConfig#flatlandsGenerationParams}'s default matches
     * exactly (49 blocks of floor from y=0) - same known limitation as TFM's own real code
     * if that config is changed to a different total height, not something TFM itself
     * computes dynamically either.
     */
    private void createWorld(String name) {
        RigelConfig config = plugin.rigelConfig();
        WorldCreator creator = new WorldCreator(name).generateStructures(false);
        cleanroomGeneratorBridge.resolveGenerator(name, config.flatlandsGenerationParams())
                .ifPresentOrElse(creator::generator, () -> creator.type(WorldType.FLAT));

        World world = creator.createWorld();
        if (world != null) {
            world.setSpawnFlags(false, false);
            world.setSpawnLocation(0, 50, 0);
        }
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
