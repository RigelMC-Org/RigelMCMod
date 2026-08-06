package org.rigelmc.protect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * Covers the TFM-syntax rule parsing/matching directly - both classes are pure Java (no
 * Bukkit types), so this runs without MockBukkit.
 */
class CommandAccessRegistryTest {

    private static final Logger LOGGER = Logger.getLogger("CommandAccessRegistryTest");

    @Test
    void bareCommandMatchesRegardlessOfArguments() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        registry.reload(List.of("n:b:/execute:_"));

        assertTrue(registry.match("execute", new String[0]).isPresent());
        assertTrue(registry.match("execute", new String[] {"run", "say", "hi"}).isPresent());
    }

    @Test
    void nobodyTierResolvesToANullRequiredRankId() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        registry.reload(List.of("n:b:/execute:_"));

        assertNull(registry.match("execute", new String[0]).orElseThrow().requiredRankId());
    }

    @Test
    void rankTokensResolveToRigelMcModRankIds() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        registry.reload(List.of("m:b:/vanish:_", "a:b:/fly:_", "s:b:/fill:_"));

        assertEquals("moderator", registry.match("vanish", new String[0]).orElseThrow().requiredRankId());
        assertEquals("admin", registry.match("fly", new String[0]).orElseThrow().requiredRankId());
        assertEquals("senior_admin", registry.match("fill", new String[0]).orElseThrow().requiredRankId());
    }

    @Test
    void subCommandPatternOnlyBlocksThatSpecificSubCommand() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        registry.reload(List.of("s:b:/mail sendall:_"));

        assertTrue(registry.match("mail", new String[] {"sendall"}).isPresent());
        assertFalse(registry.match("mail", new String[] {"send", "Steve", "hi"}).isPresent());
        assertFalse(registry.match("mail", new String[0]).isPresent()); // bare /mail unaffected
    }

    @Test
    void singleWildcardMatchesExactlyOneArgument() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        registry.reload(List.of("s:b:/give {?} bedrock {*}:_"));

        assertTrue(registry.match("give", new String[] {"Steve", "bedrock", "64"}).isPresent());
        assertFalse(registry.match("give", new String[] {"Steve", "dirt", "64"}).isPresent());
        assertFalse(registry.match("give", new String[] {"Steve", "bedrock"}).isPresent()); // {*} needs 1+
    }

    @Test
    void trailingMultiWildcardMatchesOneOrMoreArguments() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        registry.reload(List.of("s:b:/clear {*}:_"));

        assertFalse(registry.match("clear", new String[0]).isPresent()); // bare /clear allowed
        assertTrue(registry.match("clear", new String[] {"inventory"}).isPresent());
        assertTrue(registry.match("clear", new String[] {"inventory", "Steve"}).isPresent());
    }

    @Test
    void mostSpecificRuleWinsOverABroaderOne() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        // A bare "/clear" rule (matches any/no args) coexists with a more specific
        // "/clear inventory" carve-out at a different tier - the more specific rule must
        // be checked first regardless of config declaration order.
        registry.reload(List.of("s:b:/clear:_", "m:b:/clear inventory:_"));

        assertEquals(
                "moderator", registry.match("clear", new String[] {"inventory"}).orElseThrow().requiredRankId());
        assertEquals(
                "senior_admin", registry.match("clear", new String[] {"other"}).orElseThrow().requiredRankId());
        assertEquals(
                "senior_admin", registry.match("clear", new String[0]).orElseThrow().requiredRankId());
    }

    @Test
    void defaultMessageIsUsedForUnderscoreOrMissingMessage() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        registry.reload(List.of("n:b:/foo:_", "n:b:/bar"));

        assertEquals(
                "That command is blocked.",
                PlainTextComponentSerializer.plainText().serialize(registry.match("foo", new String[0]).orElseThrow().message()));
        assertEquals(
                "That command is blocked.",
                PlainTextComponentSerializer.plainText().serialize(registry.match("bar", new String[0]).orElseThrow().message()));
    }

    @Test
    void customMessageIsColorized() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        registry.reload(List.of("n:b:/foo:&cDo not do that."));

        assertEquals(
                "Do not do that.",
                PlainTextComponentSerializer.plainText().serialize(registry.match("foo", new String[0]).orElseThrow().message()));
    }

    @Test
    void malformedEntriesAreSkippedNotThrown() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        registry.reload(List.of("not-a-valid-entry", "n:b:/valid:_", "x:y:/bad-tier:_"));

        assertTrue(registry.match("valid", new String[0]).isPresent());
        assertEquals(1, registry.ruleCount());
    }

    @Test
    void reloadReplacesThePreviousRuleSetEntirely() {
        CommandAccessRegistry registry = new CommandAccessRegistry(LOGGER);
        registry.reload(List.of("n:b:/old:_"));
        assertTrue(registry.match("old", new String[0]).isPresent());

        registry.reload(List.of("n:b:/new:_"));
        assertFalse(registry.match("old", new String[0]).isPresent());
        assertTrue(registry.match("new", new String[0]).isPresent());
    }
}
