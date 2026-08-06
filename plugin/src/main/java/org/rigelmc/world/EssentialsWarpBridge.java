package org.rigelmc.world;

import com.earth2me.essentials.api.IWarps;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.ess3.api.IEssentials;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Removes any EssentialsX {@code /warp} whose stored location falls inside a given world -
 * used by {@link FlatlandsService} so a wipe doesn't leave behind warps pointing at terrain
 * that no longer exists. The regenerated flatlands world reuses the exact same world name,
 * so a stale warp doesn't fail loudly - it silently teleports someone into whatever the new
 * terrain happens to be at those old coordinates (which could be mid-air, underground, or
 * otherwise nonsensical against a freshly generated flat world).
 *
 * <p>Unlike every other bridge in this codebase, EssentialsX is a hard requirement for this
 * project (see README "Requirements"), not an optional soft dependency - but this still
 * degrades gracefully (does nothing, logs nothing) if it's somehow absent or not yet
 * enabled at the moment a wipe finishes, rather than assuming it's always there. Uses
 * Essentials' own public API directly ({@code compileOnly}, matching this project's
 * precedent for WorldEdit/PacketEvents - see {@code plugin/build.gradle.kts}) rather than
 * reflection, since the warp API surface used here is small and has been stable for years.
 * {@code Essentials} (the plugin's own main class) implements {@code net.ess3.api.IEssentials}
 * directly (confirmed from EssentialsX's real source) - no {@code ServicesManager} lookup
 * needed, just the standard "get the plugin instance, cast it" pattern every third-party
 * Essentials integration has used since the original Essentials.</p>
 *
 * <p><b>Deprecation warning is expected and accepted</b>, matching this project's own
 * precedent for {@code protect.antigrief.GameplayGuard}'s unavoidable ones: {@code
 * IEssentials#getWarps()}'s real, literal declared return type is the older {@code
 * com.earth2me.essentials.api.IWarps} (marked {@code @Deprecated} by Essentials itself in
 * favor of {@code net.ess3.api.IWarps}, which only wraps it with no new methods) - confirmed
 * by attempting the narrower type first and getting a real "incompatible types" compile
 * error, not a guess. There is no way to call {@code getWarps()} at all without triggering
 * this warning; it doesn't fail {@code gradle check} (Checkstyle/SpotBugs both pass).</p>
 */
public final class EssentialsWarpBridge {

    private final Logger logger;

    public EssentialsWarpBridge(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Must be called on the main thread - Essentials' warp API isn't documented as
     * thread-safe, and neither is Bukkit's {@link Location}/{@link org.bukkit.World}.
     *
     * @return how many warps were removed. Always {@code 0} if Essentials isn't installed
     *     or isn't enabled right now.
     */
    public int removeWarpsInWorld(@NotNull String worldName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Essentials");
        if (!(plugin instanceof IEssentials essentials) || !plugin.isEnabled()) {
            return 0;
        }
        IWarps warps = essentials.getWarps();
        if (warps == null) {
            return 0;
        }

        // Copy first - removeWarp() below would otherwise mutate the collection getList()
        // hands back while this loop is still iterating it.
        List<String> names = new ArrayList<>(warps.getList());
        int removed = 0;
        for (String name : names) {
            try {
                Location location = warps.getWarp(name);
                if (location.getWorld() != null && location.getWorld().getName().equalsIgnoreCase(worldName)) {
                    warps.removeWarp(name);
                    removed++;
                }
            } catch (Exception e) {
                // IWarps#getWarp/#removeWarp both declare checked `Exception` as their real,
                // literal signature (Essentials' own API, not this project's choice) - no
                // narrower type exists to catch instead.
                logger.log(Level.WARNING,
                        "Failed to inspect/remove warp '" + name + "' during a flatlands wipe", e);
            }
        }
        return removed;
    }
}
