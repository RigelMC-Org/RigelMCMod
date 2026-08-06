package org.rigelmc.punish.warn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rigelmc.data.TestDatabase;

class StrikeServiceTest {

    private static final long HOUR_MILLIS = 60L * 60L * 1000L;

    private HikariDataSource dataSource;
    private StrikeService strikeService;

    @BeforeEach
    void setUp(@TempDir java.io.File tempDir) throws Exception {
        this.dataSource = TestDatabase.create(tempDir);
        this.strikeService = new StrikeService(new StrikeDao(dataSource));
    }

    @AfterEach
    void tearDown() {
        // Close Hikari's pooled connections before JUnit tries to delete the @TempDir -
        // on Windows, a still-open SQLite file handle makes that cleanup fail.
        dataSource.close();
    }

    @Test
    void strikesAccumulateWithinTheDecayWindow() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = 10_000_000L;

        assertEquals(1, strikeService.recordStrike(uuid, 24, now));
        assertEquals(2, strikeService.recordStrike(uuid, 24, now + HOUR_MILLIS));
        assertEquals(3, strikeService.recordStrike(uuid, 24, now + 2 * HOUR_MILLIS));
    }

    @Test
    void strikesFullyResetOncePastTheDecayWindow() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = 10_000_000L;

        assertEquals(1, strikeService.recordStrike(uuid, 24, now));
        assertEquals(2, strikeService.recordStrike(uuid, 24, now + HOUR_MILLIS));

        // 25 hours after the last strike (> the 24h decay window) - a full reset, not a
        // gradual step-down, matching TFM's own StrikeRecord#effectiveCount semantics.
        long wellPastDecay = now + HOUR_MILLIS + 25 * HOUR_MILLIS;
        assertEquals(1, strikeService.recordStrike(uuid, 24, wellPastDecay));
    }

    @Test
    void zeroOrNegativeDecayHoursMeansStrikesNeverReset() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = 10_000_000L;

        assertEquals(1, strikeService.recordStrike(uuid, 0, now));
        // A huge gap - would fully decay under any positive window, but 0 disables decay.
        long muchLater = now + 1000L * HOUR_MILLIS;
        assertEquals(2, strikeService.recordStrike(uuid, 0, muchLater));
    }

    @Test
    void effectiveCountDoesNotMutateState() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = 10_000_000L;

        strikeService.recordStrike(uuid, 24, now);
        assertEquals(1, strikeService.effectiveCount(uuid, 24, now + HOUR_MILLIS));
        assertEquals(1, strikeService.effectiveCount(uuid, 24, now + HOUR_MILLIS));
        // Recording afterward still increments from the same base, confirming the reads
        // above never wrote anything.
        assertEquals(2, strikeService.recordStrike(uuid, 24, now + 2 * HOUR_MILLIS));
    }
}
