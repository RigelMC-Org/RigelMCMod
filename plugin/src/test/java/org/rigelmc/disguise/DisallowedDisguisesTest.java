package org.rigelmc.disguise;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.rigelmc.core.RigelConfig;

/**
 * Pure-Java tests, no MockBukkit/server needed - {@link RigelConfig} wraps a plain-parsed
 * {@link YamlConfiguration}, matching {@code RigelConfigTest}'s own established pattern.
 */
class DisallowedDisguisesTest {

    @Test
    void fallsBackToTfmsRealDefaultListWhenConfigIsEmpty() {
        DisallowedDisguises disguises = new DisallowedDisguises(configFrom(""));

        // TFM's own real DEFAULT_FORBIDDEN entries.
        assertFalse(disguises.isAllowed("WITHER"));
        assertFalse(disguises.isAllowed("ender_dragon")); // case-insensitive
        assertFalse(disguises.isAllowed("PLAYER"));
        assertTrue(disguises.isAllowed("ZOMBIE"));
    }

    @Test
    void explicitConfigOverridesDefaultsEntirely() {
        DisallowedDisguises disguises = new DisallowedDisguises(configFrom(
                """
                fun:
                  disguise:
                    forbidden-types:
                      - creeper
                """));

        assertFalse(disguises.isAllowed("CREEPER"));
        // WITHER is in TFM's default list, but an explicit non-empty list replaces it entirely.
        assertTrue(disguises.isAllowed("WITHER"));
    }

    @Test
    void disabledFlagDefaultsToEnabledAndIsPurelyInMemory() {
        DisallowedDisguises disguises = new DisallowedDisguises(configFrom(""));
        assertFalse(disguises.isDisabled());

        disguises.setDisabled(true);
        assertTrue(disguises.isDisabled());
    }

    @Test
    void reloadPicksUpAFreshlyRebuiltConfig() {
        DisallowedDisguises disguises = new DisallowedDisguises(configFrom(""));
        assertTrue(disguises.isAllowed("CREEPER"));

        disguises.reload(configFrom(
                """
                fun:
                  disguise:
                    forbidden-types:
                      - creeper
                """));

        assertFalse(disguises.isAllowed("CREEPER"));
    }

    private static RigelConfig configFrom(String yaml) {
        return new RigelConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }
}
