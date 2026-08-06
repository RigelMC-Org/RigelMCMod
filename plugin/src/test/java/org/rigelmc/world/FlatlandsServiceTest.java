package org.rigelmc.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Only the pure "is a wipe due" decision is unit-tested - the actual world create/
 * delete/recreate I/O in {@link FlatlandsService} needs a live Bukkit world container,
 * per the class's own javadoc caveat.
 */
class FlatlandsServiceTest {

    @Test
    void notDueBeforeIntervalElapses() {
        long lastWipe = 1_000_000L;
        Duration interval = Duration.ofHours(24);
        long now = lastWipe + Duration.ofHours(23).toMillis();

        assertFalse(FlatlandsService.isWipeDue(lastWipe, interval, now));
    }

    @Test
    void dueExactlyAtTheIntervalBoundary() {
        long lastWipe = 1_000_000L;
        Duration interval = Duration.ofHours(24);
        long now = lastWipe + interval.toMillis();

        assertTrue(FlatlandsService.isWipeDue(lastWipe, interval, now));
    }

    @Test
    void dueAfterTheIntervalHasPassed() {
        long lastWipe = 1_000_000L;
        Duration interval = Duration.ofHours(24);
        long now = lastWipe + Duration.ofHours(25).toMillis();

        assertTrue(FlatlandsService.isWipeDue(lastWipe, interval, now));
    }
}
