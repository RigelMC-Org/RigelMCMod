package org.rigelmc.world;

import java.util.Optional;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Presence-detected bridge to the standalone CleanroomGenerator plugin
 * (<a href="https://github.com/nvx/CleanroomGenerator">github.com/nvx/CleanroomGenerator</a>),
 * used for flatlands generation instead of vanilla's own superflat generator when it's
 * installed - user-requested. TFM itself bundles its own internal equivalent ({@code
 * bridge}-adjacent {@code world.Flatlands}/{@code CleanroomChunkGenerator}, studied
 * directly) using the exact same pipe-delimited {@code "height|blockType|height|blockType|
 * ..."} parameter format this class passes straight through, unparsed, to the real
 * plugin's own generator - see {@code core.RigelConfig#flatlandsGenerationParams} for the
 * format and TFM's own real default value.
 *
 * <p>Zero new dependency and zero reflection: {@code Plugin#getDefaultWorldGenerator}
 * (the method actually called here) is a standard method every Bukkit plugin already
 * exposes, not something specific to CleanroomGenerator's own API - this bridge only ever
 * detects the plugin is present and asks it for a generator through that existing
 * interface. CleanroomGenerator is AGPL-3.0 licensed; this never vendors, copies, or links
 * against its source in any way, purely a soft runtime dependency, same category as
 * CoreProtect/WorldEdit/LibsDisguises elsewhere in this project. If it isn't installed,
 * {@link FlatlandsService} falls back to vanilla's own {@code WorldType.FLAT} generator.</p>
 */
public final class CleanroomGeneratorBridge {

    private static final String PLUGIN_NAME = "CleanroomGenerator";

    private final Function<String, Plugin> pluginLookup;

    public CleanroomGeneratorBridge() {
        this(name -> {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            return plugin != null && plugin.isEnabled() ? plugin : null;
        });
    }

    /** Test-only constructor - injects the plugin lookup directly, no live Bukkit server needed. */
    CleanroomGeneratorBridge(@NotNull Function<String, Plugin> pluginLookup) {
        this.pluginLookup = pluginLookup;
    }

    /**
     * @param worldName the world being created - passed straight through to the real
     *     plugin's own generator (its current implementation ignores it, but forwarding
     *     it faithfully costs nothing and stays correct if a future version starts using it)
     * @param generationParams the pipe-delimited layer string - see class javadoc
     * @return the real plugin's own {@link ChunkGenerator} for this world, or empty if
     *     CleanroomGenerator isn't installed/enabled
     */
    @NotNull
    public Optional<ChunkGenerator> resolveGenerator(@NotNull String worldName, @NotNull String generationParams) {
        Plugin plugin = pluginLookup.apply(PLUGIN_NAME);
        if (plugin == null) {
            return Optional.empty();
        }
        ChunkGenerator generator = plugin.getDefaultWorldGenerator(worldName, generationParams);
        return Optional.ofNullable(generator);
    }
}
