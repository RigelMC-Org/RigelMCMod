package org.rigelmc.webpanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure {@code java.nio.file} tests, no live server needed - see {@link
 * SchematicsService}'s own javadoc for why the base directory is taken directly rather than
 * detected from a live {@code RigelMCMod}/Bukkit instance. The {@link #resolve} tests are
 * the actual security coverage: every one of the traversal attempts below must come back
 * {@code null}, not throw and not silently succeed.
 */
class SchematicsServiceTest {

    @Test
    void listsFilesAtBaseAndOneSubfolderLevel(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("spawn.schem"), "a");
        Path playerDir = Files.createDirectory(tempDir.resolve("LightWarp"));
        Files.writeString(playerDir.resolve("build.schem"), "bb");

        SchematicsService service = new SchematicsService(tempDir);
        List<SchematicsService.Entry> entries = service.list();

        assertEquals(2, entries.size());
        assertEquals("LightWarp/build.schem", entries.get(0).relativePath()); // alphabetically first
        assertEquals(2, entries.get(0).sizeBytes());
        assertEquals("spawn.schem", entries.get(1).relativePath());
        assertEquals(1, entries.get(1).sizeBytes());
    }

    @Test
    void listReturnsEmptyForMissingDirectory(@TempDir Path tempDir) {
        SchematicsService service = new SchematicsService(tempDir.resolve("does-not-exist"));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void listReturnsEmptyWhenBaseDirectoryIsNull() {
        SchematicsService service = new SchematicsService(null);
        assertTrue(service.list().isEmpty());
    }

    @Test
    void resolveReturnsTheRealFileForAValidRelativePath(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("spawn.schem"), "hello");
        SchematicsService service = new SchematicsService(tempDir);

        Path resolved = service.resolve("spawn.schem");

        assertNotNull(resolved);
        assertEquals("hello", Files.readString(resolved));
    }

    @Test
    void resolveWorksOneLevelDeepForPerPlayerFolders(@TempDir Path tempDir) throws IOException {
        Path playerDir = Files.createDirectory(tempDir.resolve("LightWarp"));
        Files.writeString(playerDir.resolve("build.schem"), "x");
        SchematicsService service = new SchematicsService(tempDir);

        assertNotNull(service.resolve("LightWarp/build.schem"));
    }

    @Test
    void resolveRejectsDirectTraversalOutsideTheBaseDirectory(@TempDir Path tempDir) throws IOException {
        Path base = Files.createDirectory(tempDir.resolve("schematics"));
        Path secretOutsideBase = tempDir.resolve("secret.txt");
        Files.writeString(secretOutsideBase, "should never be reachable");
        SchematicsService service = new SchematicsService(base);

        assertNull(service.resolve("../secret.txt"));
        assertNull(service.resolve("../../secret.txt"));
        assertNull(service.resolve("subdir/../../secret.txt"));
    }

    @Test
    void resolveRejectsAnAbsolutePathEscapingTheBaseDirectory(@TempDir Path tempDir) throws IOException {
        Path base = Files.createDirectory(tempDir.resolve("schematics"));
        Path secretOutsideBase = tempDir.resolve("secret.txt");
        Files.writeString(secretOutsideBase, "nope");
        SchematicsService service = new SchematicsService(base);

        assertNull(service.resolve(secretOutsideBase.toAbsolutePath().toString()));
    }

    @Test
    void resolveRejectsANonexistentFile(@TempDir Path tempDir) {
        SchematicsService service = new SchematicsService(tempDir);
        assertNull(service.resolve("nope.schem"));
    }

    @Test
    void resolveRejectsADirectory(@TempDir Path tempDir) throws IOException {
        Files.createDirectory(tempDir.resolve("LightWarp"));
        SchematicsService service = new SchematicsService(tempDir);

        assertNull(service.resolve("LightWarp"));
    }

    @Test
    void resolveReturnsNullWhenBaseDirectoryIsNull() {
        SchematicsService service = new SchematicsService(null);
        assertNull(service.resolve("spawn.schem"));
    }
}
