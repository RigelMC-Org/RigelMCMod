package org.rigelmc.protect.antigrief;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link CommandBlockGuard#isBlockedAdminBlock} - the pure half of the guard, split
 * out precisely so it can be exercised without a live server (same reasoning as {@code
 * guild.plot.PlotWorldTerrainTest}). The event plumbing itself needs a real Bukkit server
 * and is covered by the manual checklist instead.
 */
class CommandBlockGuardTest {

    @Test
    void everyCommandBlockVariantIsBlocked() {
        // All three can actually run a command, which is the whole point - a plain
        // COMMAND_BLOCK check would miss the repeating one that caused the reported incident.
        assertTrue(CommandBlockGuard.isBlockedAdminBlock(Material.COMMAND_BLOCK, false));
        assertTrue(CommandBlockGuard.isBlockedAdminBlock(Material.CHAIN_COMMAND_BLOCK, false));
        assertTrue(CommandBlockGuard.isBlockedAdminBlock(Material.REPEATING_COMMAND_BLOCK, false));
    }

    @Test
    void commandBlocksAreBlockedRegardlessOfTheStructureBlockToggle() {
        // block-structure-blocks only ever widens the set - it must never gate the command
        // blocks themselves, which are unconditional.
        for (Material material : new Material[] {
                Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK}) {
            assertTrue(CommandBlockGuard.isBlockedAdminBlock(material, true), material.name());
            assertTrue(CommandBlockGuard.isBlockedAdminBlock(material, false), material.name());
        }
    }

    @Test
    void structureAndJigsawBlocksFollowTheirOwnToggle() {
        assertTrue(CommandBlockGuard.isBlockedAdminBlock(Material.STRUCTURE_BLOCK, true));
        assertTrue(CommandBlockGuard.isBlockedAdminBlock(Material.JIGSAW, true));
        assertFalse(CommandBlockGuard.isBlockedAdminBlock(Material.STRUCTURE_BLOCK, false));
        assertFalse(CommandBlockGuard.isBlockedAdminBlock(Material.JIGSAW, false));
    }

    @Test
    void ordinaryBuildingBlocksAreNeverBlocked() {
        for (Material material : new Material[] {
                Material.STONE, Material.OAK_PLANKS, Material.CHEST, Material.REDSTONE_BLOCK,
                Material.REPEATER, Material.DISPENSER, Material.AIR}) {
            assertFalse(CommandBlockGuard.isBlockedAdminBlock(material, true), material.name());
        }
    }

    @Test
    void isCommandBlockCoversExactlyTheThreeCommandRunningVariants() {
        assertTrue(CommandBlockGuard.isCommandBlock(Material.COMMAND_BLOCK));
        assertTrue(CommandBlockGuard.isCommandBlock(Material.CHAIN_COMMAND_BLOCK));
        assertTrue(CommandBlockGuard.isCommandBlock(Material.REPEATING_COMMAND_BLOCK));
        // Structure/jigsaw are blocked from placement but can't run commands, so they must
        // NOT be treated as command blocks by the removal path.
        assertFalse(CommandBlockGuard.isCommandBlock(Material.STRUCTURE_BLOCK));
        assertFalse(CommandBlockGuard.isCommandBlock(Material.JIGSAW));
        assertFalse(CommandBlockGuard.isCommandBlock(Material.STONE));
    }
}
