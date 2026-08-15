package org.rigelmc.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the "Error sending packet clientbound/minecraft:system_chat"
 * crash: an announcement whose URL sits directly against an unresolved/misspelled
 * MiniMessage closing tag (no whitespace between them, e.g. a typo'd {@code </aqla>} for
 * {@code </aqua>}) used to have that tag text swallowed into the "URL" match, producing a
 * string {@code ClickEvent.openUrl} happily accepts but {@code java.net.URI.create} (what
 * Paper's packet encoder eventually calls) rejects - crashing the packet send for every
 * player who receives the broadcast. See {@link AnnounceModule#URL_PATTERN}'s javadoc.
 */
class AnnounceModuleUrlPatternTest {

    @Test
    void stopsAtAnAdjacentUnresolvedClosingTag() {
        Matcher matcher = AnnounceModule.URL_PATTERN.matcher("https://discord.rigelmc.org</aqla>");

        assertTrue(matcher.find());
        assertEquals("https://discord.rigelmc.org", matcher.group());
        // The whole point: whatever the pattern matched must be a URI Paper can actually
        // encode into a packet - this is the exact call that used to throw in production.
        assertDoesNotThrowUriCreate(matcher.group());
    }

    @Test
    void stopsAtAWellFormedTagWithNoIntermediateWhitespace() {
        Matcher matcher = AnnounceModule.URL_PATTERN.matcher("https://example.com<red>bold");

        assertTrue(matcher.find());
        assertEquals("https://example.com", matcher.group());
    }

    @Test
    void stillExcludesTrailingSentencePunctuation() {
        Matcher matcher = AnnounceModule.URL_PATTERN.matcher("check out https://example.com. it's great");

        assertTrue(matcher.find());
        assertEquals("https://example.com", matcher.group());
    }

    @Test
    void doesNotMatchWhenThereIsNoUrl() {
        Matcher matcher = AnnounceModule.URL_PATTERN.matcher("<gray>Welcome to the server!");

        assertFalse(matcher.find());
    }

    private static void assertDoesNotThrowUriCreate(String url) {
        URI.create(url); // throws IllegalArgumentException on failure - that's the assertion
    }
}
