package org.rigelmc.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Fills in any {@code config.yml} keys a plugin update introduced that an already-deployed
 * config.yml doesn't have yet - without touching a single key the operator already set.
 * Without this, every new config option added in a future release would silently read as
 * its Java-side fallback default forever on an existing install (since {@link RigelConfig}
 * always calls the two-arg {@code getString(path, default)} family) - functional, but the
 * key would never actually appear in the operator's config.yml for them to discover and
 * customize, and would look identical to "not configured" versus "configured to look like
 * the default" - one config.yml gaining unexplained new blank-looking sections isn't
 * fixed by that, it needs the actual key written to disk.
 *
 * <p>TFM's own {@code MainConfig#load()} solves the identical problem, but keyed off a
 * hand-maintained {@code ConfigEntry} enum that has to be kept in sync with the bundled
 * config.yml by hand, in a second place, forever. This instead walks the bundled default's
 * own key tree directly via {@link org.bukkit.configuration.ConfigurationSection#getKeys},
 * so any key present in {@code resources/config.yml} is automatically covered - no second
 * list to maintain, and no risk of the two drifting apart.</p>
 *
 * <p>Deliberately additive-only: a key present on disk is never touched, overwritten, or
 * removed, no matter what the bundled default says it should be now - only keys genuinely
 * <em>missing</em> from the live file get added. Comments are not preserved (a known
 * {@link YamlConfiguration} limitation - it round-trips values only), but nothing is lost
 * that the operator didn't already have, and nothing they set is reformatted or reordered
 * beyond the newly appended keys.</p>
 */
public final class ConfigUpdater {

    private ConfigUpdater() {}

    /**
     * @param liveFile the on-disk {@code config.yml} to check/update in place
     * @param defaultResource the bundled default config, e.g. from {@code
     *     JavaPlugin#getResource("config.yml")} - closed by this method
     * @param logger where to report what happened
     * @return {@code true} if one or more missing keys were found and written back to
     *     {@code liveFile}; {@code false} if nothing was missing, or if the update
     *     couldn't be performed (already-logged: an unparseable live/default file, or a
     *     failure to save) - in every {@code false} case, {@code liveFile} is left exactly
     *     as it was found
     */
    public static boolean update(
            @NotNull File liveFile, @NotNull InputStream defaultResource, @NotNull Logger logger) {
        YamlConfiguration live = new YamlConfiguration();
        try {
            live.load(liveFile);
        } catch (IOException | InvalidConfigurationException e) {
            logger.log(Level.WARNING,
                    "Could not read config.yml to check for new keys introduced by an update - skipping the"
                            + " config updater this run. Fix the syntax error (see /rmcm reload for details)"
                            + " and it'll be checked again on the next reload/restart.",
                    e);
            return false;
        }

        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(defaultResource, StandardCharsets.UTF_8)) {
            defaults.load(reader);
        } catch (IOException | InvalidConfigurationException e) {
            logger.log(Level.SEVERE,
                    "Could not read RigelMCMod's own bundled default config.yml - this is a packaging bug,"
                            + " please report it.",
                    e);
            return false;
        }

        List<String> added = new ArrayList<>();
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path)) {
                continue; // an intermediate section header, not a leaf value - nothing to copy
            }
            if (!live.isSet(path)) {
                live.set(path, defaults.get(path));
                added.add(path);
            }
        }

        if (added.isEmpty()) {
            return false;
        }

        try {
            live.save(liveFile);
        } catch (IOException e) {
            logger.log(Level.WARNING,
                    "Found " + added.size() + " new config.yml key(s) introduced by an update, but failed to"
                            + " save them back to disk - RigelMCMod will keep using its built-in defaults for"
                            + " them until this is fixed.",
                    e);
            return false;
        }

        logger.info("config.yml updated - added " + added.size() + " new key(s) introduced by a plugin update: "
                + String.join(", ", added));
        return true;
    }
}
