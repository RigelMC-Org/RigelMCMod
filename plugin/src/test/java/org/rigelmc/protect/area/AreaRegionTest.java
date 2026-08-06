package org.rigelmc.protect.area;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-Java tests - no database, no Bukkit - see {@link AreaRegion}'s javadoc for why this is possible. */
class AreaRegionTest {

    private static AreaRecord record(String name, int minX, int minZ, int maxX, int maxZ, int priority, long createdAt) {
        return new AreaRecord(
                1, name, "world", "CUBOID", minX, 0, minZ, maxX, 10, maxZ, priority, true, null, null, createdAt, null, createdAt);
    }

    // ---- containment / volume -----------------------------------------------------------

    @Test
    void containsIsInclusiveOnEveryEdge() {
        AreaRegion region = new AreaRegion(record("A", 0, 0, 10, 10, 0, 1), Set.of(), Map.of());
        assertTrue(region.contains("world", 0, 0, 0));
        assertTrue(region.contains("world", 10, 10, 10));
        assertFalse(region.contains("world", 11, 0, 0));
        assertFalse(region.contains("world", 0, 0, -1));
    }

    @Test
    void containsRequiresTheSameWorld() {
        AreaRegion region = new AreaRegion(record("A", 0, 0, 10, 10, 0, 1), Set.of(), Map.of());
        assertFalse(region.contains("nether", 5, 0, 5));
    }

    @Test
    void volumeCountsEveryBlockInclusive() {
        // A 1x1x1 region (min == max on every axis) is exactly 1 block, not 0. record()'s
        // own Y range is fixed at 0-10 (11 blocks), so this needs its own explicit record
        // rather than that helper.
        AreaRegion single = new AreaRegion(
                new AreaRecord(1, "A", "world", "CUBOID", 0, 0, 0, 0, 0, 0, 0, true, null, null, 1L, null, 1L),
                Set.of(), Map.of());
        assertEquals(1L, single.volume());

        AreaRegion tenCubed = new AreaRegion(
                new AreaRecord(1, "B", "world", "CUBOID", 0, 0, 0, 9, 9, 9, 0, true, null, null, 1L, null, 1L),
                Set.of(), Map.of());
        assertEquals(1000L, tenCubed.volume());
    }

    // ---- effective flag resolution --------------------------------------------------------

    @Test
    void effectiveFlagFallsBackToCodedDefaultWithNoOverride() {
        AreaRegion region = new AreaRegion(record("A", 0, 0, 1, 1, 0, 1), Set.of(), Map.of());
        assertEquals(AreaFlag.BUILD.defaultValue(), region.effectiveFlag(AreaFlag.BUILD));
        assertEquals(AreaFlag.PVP.defaultValue(), region.effectiveFlag(AreaFlag.PVP));
    }

    @Test
    void effectiveFlagUsesTheOverrideWhenPresent() {
        Map<AreaFlag, Boolean> overrides = new EnumMap<>(AreaFlag.class);
        overrides.put(AreaFlag.PVP, !AreaFlag.PVP.defaultValue());
        AreaRegion region = new AreaRegion(record("A", 0, 0, 1, 1, 0, 1), Set.of(), overrides);
        assertEquals(!AreaFlag.PVP.defaultValue(), region.effectiveFlag(AreaFlag.PVP));
    }

    // ---- member/owner bypass ---------------------------------------------------------------

    @Test
    void isMemberOrOwnerRecognizesTheOwner() {
        UUID owner = UUID.randomUUID();
        AreaRecord withOwner = new AreaRecord(
                1, "A", "world", "CUBOID", 0, 0, 0, 1, 1, 1, 0, true, owner, null, 1L, null, 1L);
        AreaRegion region = new AreaRegion(withOwner, Set.of(), Map.of());
        assertTrue(region.isMemberOrOwner(owner));
    }

    @Test
    void isMemberOrOwnerRecognizesAnExplicitMember() {
        UUID member = UUID.randomUUID();
        AreaRegion region = new AreaRegion(record("A", 0, 0, 1, 1, 0, 1), Set.of(member), Map.of());
        assertTrue(region.isMemberOrOwner(member));
    }

    @Test
    void isMemberOrOwnerRejectsAnUnrelatedPlayer() {
        AreaRegion region = new AreaRegion(record("A", 0, 0, 1, 1, 0, 1), Set.of(UUID.randomUUID()), Map.of());
        assertFalse(region.isMemberOrOwner(UUID.randomUUID()));
    }

    // ---- overlap tie-break: priority -> volume -> recency ------------------------------------

    @Test
    void selectEffectiveReturnsNullForNoCandidates() {
        assertNull(AreaRegion.selectEffective(List.of()));
    }

    @Test
    void selectEffectiveReturnsTheSoleCandidate() {
        AreaRegion only = new AreaRegion(record("A", 0, 0, 1, 1, 0, 1), Set.of(), Map.of());
        assertEquals(only, AreaRegion.selectEffective(List.of(only)));
    }

    @Test
    void selectEffectiveHighestPriorityWins() {
        AreaRegion low = new AreaRegion(record("Low", 0, 0, 10, 10, 0, 1), Set.of(), Map.of());
        AreaRegion high = new AreaRegion(record("High", 0, 0, 10, 10, 5, 1), Set.of(), Map.of());
        assertEquals(high, AreaRegion.selectEffective(List.of(low, high)));
    }

    @Test
    void selectEffectiveTiesBrokenBySmallestVolume() {
        // Same priority - the smaller (nested) region should win, matching the WorldGuard
        // convention this project reuses rather than inventing a novel one.
        AreaRegion big = new AreaRegion(record("Big", 0, 0, 100, 100, 0, 1), Set.of(), Map.of());
        AreaRegion small = new AreaRegion(record("Small", 40, 40, 60, 60, 0, 1), Set.of(), Map.of());
        assertEquals(small, AreaRegion.selectEffective(List.of(big, small)));
    }

    @Test
    void selectEffectiveFinalTieBrokenByMostRecentlyCreated() {
        AreaRegion older = new AreaRegion(record("Older", 0, 0, 10, 10, 0, 1000L), Set.of(), Map.of());
        AreaRegion newer = new AreaRegion(record("Newer", 0, 0, 10, 10, 0, 2000L), Set.of(), Map.of());
        assertEquals(newer, AreaRegion.selectEffective(List.of(older, newer)));
    }
}
