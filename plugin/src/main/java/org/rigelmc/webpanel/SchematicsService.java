package org.rigelmc.webpanel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;

/**
 * Read-only listing/serving of WorldEdit/FAWE schematic files for the web panel's
 * schematics page - deliberately no upload/write endpoint exists anywhere in this class or
 * its caller ({@link WebPanelServer}), by explicit design. Same no-auth/localhost-by-default
 * security posture as every other part of this panel (see {@link WebPanelServer}'s own
 * javadoc) - the one thing this class does that the rest of the panel doesn't is serve raw
 * file bytes, so path resolution here is deliberately paranoid: every requested relative
 * path is canonicalized ({@link Path#toRealPath}, which also resolves symlinks) against the
 * canonicalized base directory and rejected outright unless the result is actually inside
 * it - the standard defense against {@code ../../etc/whatever}-style traversal.
 *
 * <p>Takes the resolved base directory directly (rather than a {@code RigelMCMod}/{@code
 * RigelConfig} reference) purely so the security-critical {@link #resolve} logic is
 * trivially unit-testable against a plain temp directory - {@link #forServer} is where the
 * live-server-dependent detection actually happens, kept separate for exactly that reason.
 * See {@code SchematicsServiceTest}.</p>
 */
final class SchematicsService {

    /** Base directory itself (depth 1) plus one level of subfolders (depth 2, e.g. FAWE's per-player folders). */
    private static final int MAX_DEPTH = 2;

    private final Path baseDirectory;

    /** @param baseDirectory the schematics directory to serve from, or {@code null} if undetectable */
    SchematicsService(@Nullable Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    /**
     * Resolves the real base directory for a live server: an explicit {@code
     * webpanel.schematics.directory} config override, or - the default - whichever of
     * "FastAsyncWorldEdit"/"WorldEdit" is installed (FAWE preferred, matching {@code
     * worldedit.WorldEditBridge}'s own tier order), its own {@code
     * JavaPlugin#getDataFolder()}{@code /schematics} - the standard Bukkit plugin-data-
     * folder convention both plugins follow. <b>Not verified against a real WorldEdit/FAWE
     * install</b> in this sandbox.
     */
    @NotNull
    static SchematicsService forServer(@NotNull RigelMCMod plugin) {
        String override = plugin.rigelConfig().webPanelSchematicsDirectory();
        if (!override.isBlank()) {
            return new SchematicsService(Path.of(override));
        }
        Plugin worldEditCompatible = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        if (worldEditCompatible == null) {
            worldEditCompatible = Bukkit.getPluginManager().getPlugin("WorldEdit");
        }
        if (worldEditCompatible == null) {
            return new SchematicsService(null);
        }
        return new SchematicsService(worldEditCompatible.getDataFolder().toPath().resolve("schematics"));
    }

    record Entry(String relativePath, long sizeBytes, long lastModifiedMillis) {}

    @NotNull
    List<Entry> list() {
        if (baseDirectory == null || !Files.isDirectory(baseDirectory)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(baseDirectory, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                try {
                    entries.add(new Entry(
                            baseDirectory.relativize(path).toString().replace('\\', '/'),
                            Files.size(path),
                            Files.getLastModifiedTime(path).toMillis()));
                } catch (IOException ignored) {
                    // Vanished or became unreadable between the walk and the stat - just skip it.
                }
            });
        } catch (IOException e) {
            return List.of();
        }
        entries.sort(Comparator.comparing(Entry::relativePath));
        return entries;
    }

    /**
     * Resolves a client-supplied relative path to an actual file for download - the real
     * security boundary of this whole class. Returns {@code null} (never throws for a
     * malicious/malformed input) if the path doesn't exist, isn't a regular file, or
     * doesn't canonicalize to somewhere inside the base directory.
     */
    @Nullable
    Path resolve(@NotNull String relativePath) {
        if (baseDirectory == null || !Files.isDirectory(baseDirectory)) {
            return null;
        }
        Path realBase;
        Path candidate;
        try {
            realBase = baseDirectory.toRealPath();
            candidate = baseDirectory.resolve(relativePath).normalize().toRealPath();
        } catch (IOException | InvalidPathException e) {
            return null; // doesn't exist, or the raw string isn't even a syntactically valid path
        }
        if (!candidate.startsWith(realBase) || !Files.isRegularFile(candidate)) {
            return null;
        }
        return candidate;
    }
}
