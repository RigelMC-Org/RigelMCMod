package org.rigelmc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure-Java, no MockBukkit needed - same rationale as {@link RigelConfigTest}: {@link
 * org.bukkit.configuration.file.YamlConfiguration} parses/writes plain files without a
 * live server.
 */
class ConfigUpdaterTest {

    private static final Logger LOGGER = Logger.getLogger(ConfigUpdaterTest.class.getName());

    @Test
    void addsAMissingKeyWithoutTouchingExistingOnes(@TempDir File tempDir) throws IOException {
        File live = writeFile(tempDir, "existing.yml", "foo: bar\n");
        String defaultYaml = "foo: should-not-overwrite\nnew-key: hello\n";

        boolean changed = ConfigUpdater.update(live, streamOf(defaultYaml), LOGGER);

        assertTrue(changed);
        YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(live);
        assertEquals("bar", reloaded.getString("foo")); // untouched
        assertEquals("hello", reloaded.getString("new-key")); // newly added
    }

    @Test
    void addsNestedMissingKeysOnly() throws IOException {
        File tempFile = File.createTempFile("rigelmc-config-updater-test", ".yml");
        tempFile.deleteOnExit();
        Files.writeString(tempFile.toPath(), "section:\n  a: 1\n", StandardCharsets.UTF_8);
        String defaultYaml = "section:\n  a: 999\n  b: 2\nother:\n  c: 3\n";

        boolean changed = ConfigUpdater.update(tempFile, streamOf(defaultYaml), LOGGER);

        assertTrue(changed);
        YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(tempFile);
        assertEquals(1, reloaded.getInt("section.a")); // untouched, not overwritten to 999
        assertEquals(2, reloaded.getInt("section.b")); // newly added
        assertEquals(3, reloaded.getInt("other.c")); // newly added, new section too
    }

    @Test
    void reportsNoChangeAndDoesNotRewriteWhenNothingIsMissing() throws IOException {
        File tempFile = File.createTempFile("rigelmc-config-updater-test", ".yml");
        tempFile.deleteOnExit();
        String content = "foo: bar\n";
        Files.writeString(tempFile.toPath(), content, StandardCharsets.UTF_8);
        long originalModified = tempFile.lastModified();

        boolean changed = ConfigUpdater.update(tempFile, streamOf("foo: something-else\n"), LOGGER);

        assertFalse(changed);
        assertEquals(content, Files.readString(tempFile.toPath())); // byte-for-byte untouched
    }

    @Test
    void malformedLiveFileIsLeftAloneAndReportedAsNoChange() throws IOException {
        File tempFile = File.createTempFile("rigelmc-config-updater-test", ".yml");
        tempFile.deleteOnExit();
        String broken = "foo: bar\n\tbadindent: oops\n";
        Files.writeString(tempFile.toPath(), broken, StandardCharsets.UTF_8);

        boolean changed = ConfigUpdater.update(tempFile, streamOf("new-key: value\n"), LOGGER);

        assertFalse(changed);
        assertEquals(broken, Files.readString(tempFile.toPath())); // untouched, not clobbered
    }

    @Test
    void listValuesAreCopiedAsMissingLeafKeysToo() throws IOException {
        File tempFile = File.createTempFile("rigelmc-config-updater-test", ".yml");
        tempFile.deleteOnExit();
        Files.writeString(tempFile.toPath(), "unrelated: true\n", StandardCharsets.UTF_8);

        boolean changed = ConfigUpdater.update(tempFile, streamOf("entries:\n  - one\n  - two\n"), LOGGER);

        assertTrue(changed);
        YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(tempFile);
        assertEquals(List.of("one", "two"), reloaded.getStringList("entries"));
    }

    private static File writeFile(File dir, String name, String content) throws IOException {
        File file = new File(dir, name);
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file;
    }

    private static ByteArrayInputStream streamOf(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
