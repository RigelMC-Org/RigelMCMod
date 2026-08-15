package org.rigelmc.world;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;

/**
 * User-requested: console/RCON-only {@code /wipeworld confirm} regenerates the server's
 * primary Overworld dimension ({@link Bukkit#getWorlds()}{@code .get(0)}, the exact "main
 * world" convention {@link FlatlandsService} itself already uses as its own evacuation
 * target) from scratch, with a fresh random seed - deleting every player's build in it.
 * Deliberately the single most destructive command in this whole plugin, and treated
 * accordingly, more cautiously than either of this project's other two world-wipe
 * commands:
 *
 * <ul>
 *   <li><b>Console/RCON only, unconditionally</b> - no in-game option at all, unlike
 *       {@code /wipeflatlands}'s dual in-game/console mode. There's no "safe, disposable
 *       sandbox" framing to fall back on here the way there is for flatlands or the guild
 *       plot world - this is the world people actually live in.</li>
 *   <li><b>No auto-wipe schedule exists for this at all</b> - it only ever runs when an
 *       operator explicitly types the command and confirms, unlike {@code
 *       world.flatlands.autowipe}. Deliberately no config key for "wipe this
 *       automatically every N hours" exists anywhere near this class - that footgun isn't
 *       worth building for a wipe this consequential.</li>
 *   <li><b>Scoped to exactly one dimension</b> - the primary Overworld only. A Nether/End
 *       (separate world folders, if present) are left completely untouched; portals into
 *       the freshly-regenerated Overworld land wherever vanilla's own portal-search
 *       algorithm puts them, same as after any other world regeneration.</li>
 * </ul>
 *
 * <p>Mirrors {@link FlatlandsService}'s in-place wipe shape (evacuate, unload, delete the
 * folder off-thread, recreate, evacuate back) with one structural difference forced by the
 * fact that the world being wiped <i>is</i> the usual evacuation target: players are moved
 * to whatever other world is currently loaded (preferring the flatlands sandbox, since it's
 * already a safe, disposable holding area this project manages) for the duration of the
 * wipe, then brought back to the fresh world's own spawn once regeneration completes.
 * Deliberately no restart-based mode built for this too (unlike flatlands' dual-mode
 * design) - keeps this addition properly scoped; see {@link FlatlandsService}'s own javadoc
 * for the reasoning behind that mode if one is ever needed here later.</p>
 */
public final class RegularWorldWipeService {

    private final RigelMCMod plugin;
    private final ExecutorService dbExecutor;
    private volatile boolean wipeInProgress;

    public RegularWorldWipeService(@NotNull RigelMCMod plugin, @NotNull ExecutorService dbExecutor) {
        this.plugin = plugin;
        this.dbExecutor = dbExecutor;
    }

    public boolean isWipeInProgress() {
        return wipeInProgress;
    }

    /**
     * Must run on the main thread. Broadcasts progress/failure itself (matching {@code
     * FlatlandsService#performWipe}'s own precedent) rather than returning a result for
     * the caller to report - the caller ({@code WorldModule}'s {@code /wipeworld}) only
     * needs to know whether to bother trying at all.
     *
     * @return {@code true} if the wipe was actually started; {@code false} if rejected
     *     outright (already in progress, or no other loaded world exists to evacuate
     *     players to) - already reported to {@code sender} in that case.
     */
    public boolean wipeNow(@NotNull org.bukkit.command.CommandSender sender) {
        if (wipeInProgress) {
            sender.sendMessage(Component.text(
                    "A world wipe is already in progress.", NamedTextColor.RED));
            return false;
        }
        World target = Bukkit.getWorlds().get(0);
        World evacuationWorld = findEvacuationWorld(target);
        if (evacuationWorld == null) {
            sender.sendMessage(Component.text(
                    "Cannot wipe the primary world - no other loaded world exists to move players to in the"
                            + " meantime. Make sure the flatlands world (or some other world) is loaded first.",
                    NamedTextColor.RED));
            return false;
        }

        wipeInProgress = true;
        String name = target.getName();
        File worldFolder = target.getWorldFolder(); // must capture now, while still loaded

        for (Player player : target.getPlayers()) {
            player.teleport(evacuationWorld.getSpawnLocation());
            player.sendMessage(Component.text(
                    "The main world is being wiped and regenerated - you've been moved out temporarily.",
                    NamedTextColor.YELLOW));
        }
        Bukkit.broadcast(Component.text(
                "The primary world is being wiped and regenerated - this will take a moment.", NamedTextColor.GOLD));

        if (!Bukkit.unloadWorld(target, false)) {
            wipeInProgress = false;
            plugin.getLogger().warning("Could not wipe the primary world '" + name + "' - Bukkit refused to unload"
                    + " it (most likely a player's evacuation teleport hasn't fully landed yet). Aborting rather"
                    + " than deleting its files while still loaded. Try /wipeworld confirm again in a moment.");
            sender.sendMessage(Component.text(
                    "Could not wipe the primary world right now - it's still in use. Try again in a moment.",
                    NamedTextColor.RED));
            return false;
        }

        dbExecutor.submit(() -> {
            deleteWorldFolderRecursively(worldFolder);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (Bukkit.getWorld(name) != null) {
                    wipeInProgress = false;
                    plugin.getLogger().severe("Primary world wipe failed - a world named '" + name + "' is loaded"
                            + " again before recreation could run. Nothing was regenerated, to avoid silently"
                            + " leaving the old world in place while claiming success.");
                    Bukkit.broadcast(Component.text(
                            "The world wipe failed unexpectedly - check the server console.", NamedTextColor.RED));
                    return;
                }
                World fresh = new WorldCreator(name).createWorld(); // vanilla generation, fresh random seed
                wipeInProgress = false;
                if (fresh == null) {
                    plugin.getLogger().severe("Primary world wipe failed - the fresh world could not be created.");
                    Bukkit.broadcast(Component.text(
                            "The world wipe failed unexpectedly - check the server console.", NamedTextColor.RED));
                    return;
                }
                for (Player player : evacuationWorld.getPlayers()) {
                    player.teleport(fresh.getSpawnLocation());
                }
                Bukkit.broadcast(Component.text(
                        "The primary world has been wiped and regenerated.", NamedTextColor.GREEN));
            });
        });
        return true;
    }

    /** @return the flatlands world if loaded, else any other currently-loaded world that isn't {@code target}, else {@code null}. */
    @Nullable
    private World findEvacuationWorld(@NotNull World target) {
        World flatlands = Bukkit.getWorld(plugin.rigelConfig().flatlandsWorldName());
        if (flatlands != null && !flatlands.equals(target)) {
            return flatlands;
        }
        List<World> loaded = Bukkit.getWorlds();
        for (World world : loaded) {
            if (!world.equals(target)) {
                return world;
            }
        }
        return null;
    }

    /** Deletes {@code worldFolder} and everything under it. Blocking file I/O - call off the main thread. */
    private void deleteWorldFolderRecursively(@NotNull File worldFolder) {
        if (!worldFolder.exists()) {
            plugin.getLogger().warning("[wipeworld] Expected primary world folder does not exist at "
                    + worldFolder.getAbsolutePath() + " - nothing to delete.");
            return;
        }
        int[] counts = {0, 0}; // {deleted, failed}
        try (java.util.stream.Stream<Path> paths = Files.walk(worldFolder.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                    counts[0]++;
                } catch (IOException e) {
                    counts[1]++;
                    plugin.getLogger().log(Level.WARNING, "Failed to delete " + path + " during primary world wipe", e);
                }
            });
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to walk primary world folder for deletion", e);
            return;
        }
        plugin.getLogger().info("[wipeworld] Deleted " + counts[0] + " file(s)/folder(s) under "
                + worldFolder.getAbsolutePath() + (counts[1] > 0 ? (", " + counts[1] + " failed - see above") : "")
                + ".");
    }
}
