package org.rigelmc.nick;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NickServiceTest {

    @Test
    void tooShortOrTooLongIsRejected() {
        assertTrue(NickService.validate("ab").isPresent()); // below MIN_LENGTH
        assertTrue(NickService.validate("x".repeat(NickService.MAX_LENGTH + 1)).isPresent());
    }

    @Test
    void invalidCharactersAreRejected() {
        assertTrue(NickService.validate("bad name").isPresent()); // whitespace
        assertTrue(NickService.validate("bad-name!").isPresent()); // punctuation
    }

    @Test
    void reasonableNicknameIsAccepted() {
        assertTrue(NickService.validate("CoolGuy99").isEmpty());
        assertTrue(NickService.validate("A_B_C").isEmpty());
    }

    @Test
    void impersonatingNicknameIsRejected() {
        assertTrue(NickService.validate("[Owner]xx").isPresent());
    }
}
