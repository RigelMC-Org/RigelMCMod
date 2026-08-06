package org.rigelmc.tag;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TagServiceTest {

    @Test
    void blankTagIsRejected() {
        assertTrue(TagService.validate("").isPresent());
        assertTrue(TagService.validate("   ").isPresent());
        assertTrue(TagService.validate("&c&r").isPresent()); // color codes only, nothing visible
    }

    @Test
    void tooLongTagIsRejected() {
        assertTrue(TagService.validate("x".repeat(TagService.MAX_LENGTH + 1)).isPresent());
    }

    @Test
    void reasonableTagIsAccepted() {
        assertTrue(TagService.validate("&aBuilder").isEmpty());
        assertTrue(TagService.validate("x".repeat(TagService.MAX_LENGTH)).isEmpty());
    }

    @Test
    void impersonatingTagIsRejected() {
        assertTrue(TagService.validate("[Owner]").isPresent());
        assertTrue(TagService.validate("&c[Admin]&r").isPresent());
    }
}
