package org.rigelmc.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * {@link Proxy}-based fake {@link Plugin} - {@link Plugin} is a large interface with no
 * {@code final} implementation to subclass; a hand-written stub of every method would be
 * both tedious and easy to get subtly wrong, so this only ever wires up the one method
 * {@link CleanroomGeneratorBridge} actually calls ({@code getDefaultWorldGenerator}) and
 * throws for anything else - matches this project's established "fakes over MockBukkit"
 * convention (see {@code protect.area.ProtectAreaServiceTest}).
 */
class CleanroomGeneratorBridgeTest {

    @Test
    void resolvesAGeneratorWhenThePluginIsPresent() {
        ChunkGenerator expected = new ChunkGenerator() {};
        CleanroomGeneratorBridge bridge = new CleanroomGeneratorBridge(
                name -> "CleanroomGenerator".equals(name) ? fakePlugin(expected) : null);

        assertTrue(bridge.resolveGenerator("flatlands", "16|stone|32|dirt|1|grass_block").isPresent());
    }

    @Test
    void resolvesEmptyWhenThePluginIsAbsent() {
        CleanroomGeneratorBridge bridge = new CleanroomGeneratorBridge(name -> null);

        assertFalse(bridge.resolveGenerator("flatlands", "16|stone|32|dirt|1|grass_block").isPresent());
    }

    @Test
    void resolvesEmptyWhenThePluginReturnsNoGeneratorForThisId() {
        CleanroomGeneratorBridge bridge = new CleanroomGeneratorBridge(
                name -> "CleanroomGenerator".equals(name) ? fakePlugin(null) : null);

        assertFalse(bridge.resolveGenerator("flatlands", "16|stone|32|dirt|1|grass_block").isPresent());
    }

    /** Only {@code getDefaultWorldGenerator(String, String)} is ever called - see this class's own javadoc. */
    private static Plugin fakePlugin(ChunkGenerator generatorToReturn) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> {
                    if (isGetDefaultWorldGenerator(method)) {
                        return generatorToReturn;
                    }
                    throw new UnsupportedOperationException(
                            "Unexpected call to Plugin#" + method.getName()
                                    + " - this test only needs getDefaultWorldGenerator(String, String)");
                });
    }

    private static boolean isGetDefaultWorldGenerator(Method method) {
        return "getDefaultWorldGenerator".equals(method.getName()) && method.getParameterCount() == 2;
    }
}
