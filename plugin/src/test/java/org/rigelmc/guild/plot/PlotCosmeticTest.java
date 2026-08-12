package org.rigelmc.guild.plot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlotCosmeticTest {

    @Test
    void byKeyResolvesCaseInsensitively() {
        assertEquals(PlotCosmetic.BORDER_STONE, PlotCosmetic.byKey("BORDER-STONE").orElseThrow());
        assertEquals(PlotCosmetic.BORDER_STONE, PlotCosmetic.byKey("border-stone").orElseThrow());
    }

    @Test
    void byKeyReturnsEmptyForAnUnknownKey() {
        assertTrue(PlotCosmetic.byKey("does-not-exist").isEmpty());
    }

    @Test
    void everyKeyIsUnique() {
        long distinctKeys = java.util.Arrays.stream(PlotCosmetic.values()).map(PlotCosmetic::key).distinct().count();
        assertEquals(PlotCosmetic.values().length, distinctKeys);
    }

    @Test
    void floorDefaultIsFree() {
        assertEquals(0L, PlotCosmetic.FLOOR_DEFAULT.price());
    }

    @Test
    void everyOtherCosmeticHasAPositivePrice() {
        for (PlotCosmetic cosmetic : PlotCosmetic.values()) {
            if (cosmetic != PlotCosmetic.FLOOR_DEFAULT) {
                assertTrue(cosmetic.price() > 0, cosmetic.key() + " should have a positive price");
            }
        }
    }
}
