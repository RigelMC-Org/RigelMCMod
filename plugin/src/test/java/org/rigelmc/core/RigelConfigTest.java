package org.rigelmc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.time.Duration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java config parsing tests - {@link YamlConfiguration} parses text without needing
 * a live server, so these run under plain JUnit with no MockBukkit/server bootstrap
 * required, per the "keeps config parsing trivially unit-testable" design note on
 * {@link RigelConfig}.
 */
class RigelConfigTest {

    @Test
    void everyModuleDefaultsEnabledExceptWebpanel() {
        RigelConfig config = configFrom("");

        assertTrue(config.isModuleEnabled("punish"));
        assertTrue(config.isModuleEnabled("protect"));
        assertFalse(config.isModuleEnabled("webpanel"));
    }

    @Test
    void explicitConfigOverridesDefaults() {
        RigelConfig config = configFrom(
                """
                modules:
                  punish:
                    enabled: false
                  webpanel:
                    enabled: true
                """);

        assertFalse(config.isModuleEnabled("punish"));
        assertTrue(config.isModuleEnabled("webpanel"));
    }

    @Test
    void banDefaultsMatchTheDesignedPolicy() {
        RigelConfig config = configFrom("");

        // /ban is a fixed 24-hour quick-ban per the Ban system design.
        assertEquals(Duration.ofHours(24), config.banDefaultDuration());
        assertEquals(Duration.ofHours(1), config.banAutoRollbackLookback());
    }

    @Test
    void webPanelDefaultsToLocalhostOnly() {
        RigelConfig config = configFrom("");

        assertEquals("127.0.0.1", config.webPanelBindAddress());
        assertEquals(8081, config.webPanelPort());
        assertFalse(config.webPanelEnabled());
    }

    private static RigelConfig configFrom(String yaml) {
        return new RigelConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }
}
