package org.rigelmc.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnnouncerServiceTest {

    @Test
    void emptyListHasNoMessagesAndNeverThrows() {
        AnnouncerService service = new AnnouncerService(List.of());

        assertFalse(service.hasMessages());
        assertTrue(service.nextMessage().isEmpty());
        assertTrue(service.nextMessage().isEmpty()); // repeatable, still no crash
    }

    @Test
    void rotatesThroughMessagesInOrderThenWraps() {
        AnnouncerService service = new AnnouncerService(List.of("<red>one", "<green>two", "<blue>three"));

        assertEquals("<red>one", service.nextMessage().orElseThrow());
        assertEquals("<green>two", service.nextMessage().orElseThrow());
        assertEquals("<blue>three", service.nextMessage().orElseThrow());
        assertEquals("<red>one", service.nextMessage().orElseThrow()); // wraps around
    }
}
