package org.rigelmc.worldedit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldEditBridgeTest {

    @Test
    void prefersFaweWhenBothArePresent() {
        WorldEditBridge bridge = new WorldEditBridge(name -> true);
        assertEquals(WorldEditBridge.Tier.FAWE, bridge.detect());
        assertTrue(bridge.isAvailable());
    }

    @Test
    void fallsBackToVanillaWorldEditWhenFaweAbsent() {
        WorldEditBridge bridge = new WorldEditBridge("WorldEdit"::equals);
        assertEquals(WorldEditBridge.Tier.WORLDEDIT, bridge.detect());
        assertTrue(bridge.isAvailable());
    }

    /**
     * Simulates the documented FAWE+Eaglercraft boot-time crash: FAWE's jar is on disk
     * (a naive "is it present" check would see it), but it crashed during its own {@code
     * onEnable()} and never became enabled. This must be treated identically to "FAWE not
     * installed at all," falling straight through to vanilla WorldEdit - not silently
     * reported as the active tier.
     */
    @Test
    void crashedButPresentFaweDegradesLikeAbsent() {
        WorldEditBridge bridge = new WorldEditBridge("WorldEdit"::equals); // FAWE's own "usable" check returns false
        assertEquals(WorldEditBridge.Tier.WORLDEDIT, bridge.detect());
    }

    @Test
    void neitherInstalledMeansUnavailable() {
        WorldEditBridge bridge = new WorldEditBridge(name -> false);
        assertEquals(WorldEditBridge.Tier.NONE, bridge.detect());
        assertFalse(bridge.isAvailable());
    }
}
