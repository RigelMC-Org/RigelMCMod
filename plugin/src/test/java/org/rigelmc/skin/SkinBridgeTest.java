package org.rigelmc.skin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SkinBridgeTest {

    @Test
    void availableWhenSkinsRestorerIsUsable() {
        SkinBridge bridge = new SkinBridge("SkinsRestorer"::equals);
        assertTrue(bridge.isAvailable());
    }

    @Test
    void unavailableWhenSkinsRestorerIsAbsent() {
        SkinBridge bridge = new SkinBridge(name -> false);
        assertFalse(bridge.isAvailable());
    }
}
