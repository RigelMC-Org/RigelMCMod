package org.rigelmc.protect.worldedit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link FragileBlockRule} - the pure flag combination deciding whether a block pops
 * off into an item.
 *
 * <p>{@code FragileBlockPolicy}'s own {@code BlockStateHolder} methods deliberately are
 * <b>not</b> covered, and cannot be: {@code worldedit-core} is a {@code compileOnly}
 * dependency absent from the test runtime classpath, so a test cannot even load a class
 * whose signatures mention {@code BlockStateHolder} - and its {@code BlockTypes}/{@code
 * BlockCategories} constants stay null until a WorldEdit platform registers at server
 * startup regardless. That is precisely why the decision was split into a WorldEdit-free
 * class, and it matches the rest of this package, which has no extent tests for the same
 * reason.</p>
 */
class FragileBlockRuleTest {

    @Test
    void aBlockDestroyedByPistonsIsFragile() {
        // isFragileWhenPushed is Minecraft's PushReaction.DESTROY - torches, saplings,
        // flowers, redstone, rails. Solid or not, that flag alone settles it.
        assertTrue(FragileBlockRule.isFragile(true, false, false, false));
        assertTrue(FragileBlockRule.isFragile(true, true, false, false));
    }

    @Test
    void aNonSolidBlockIsFragileEvenIfPistonsPushRatherThanBreakIt() {
        // The belt-and-braces half: a handful of support-requiring decorations get pushed
        // rather than destroyed, so the push flag alone would miss them.
        assertTrue(FragileBlockRule.isFragile(false, false, false, false));
    }

    @Test
    void ordinarySolidBuildingBlocksAreNotFragile() {
        // Stone, planks, wool - the overwhelming majority of what a legitimate edit places.
        assertFalse(FragileBlockRule.isFragile(false, true, false, false));
    }

    @Test
    void airAndLiquidAreNeverFragileEvenThoughTheyAreNotSolid() {
        // Both are non-solid, so without the explicit carve-out every //set air (the single
        // most common WorldEdit operation there is) and every water/lava edit would count
        // against the cap and halt long before a real fragile-block fill did.
        assertFalse(FragileBlockRule.isFragile(false, false, true, false));
        assertFalse(FragileBlockRule.isFragile(false, false, false, true));
        // The air/liquid carve-out must win even when the push flag is set.
        assertFalse(FragileBlockRule.isFragile(true, false, true, false));
        assertFalse(FragileBlockRule.isFragile(true, false, false, true));
    }
}
