package org.rigelmc.protect.crash;

import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.protect.crash.packet.CrashPacketService;
import org.rigelmc.rank.PermissionGate;

/**
 * Crash-exploit protections, both tiers: the "E1" event-level guards (pure Bukkit/Paper
 * public API, no new dependency - items, entity custom names, sign content, spawner
 * output) and the "E2" packet-level guards ({@link CrashPacketService}, needs PacketEvents
 * installed separately - see that class's javadoc for why it's a soft server dependency
 * rather than a bundled one). Both studied directly from TFM's real crash-exploit suite.
 * Each guard independently checks its own {@code protect.crash.*} enabled flag, matching
 * {@code protect.antigrief.AntiGriefModule}'s live-reload-friendly design; E2 additionally
 * no-ops entirely if PacketEvents was never found, without affecting E1 at all.
 */
public final class CrashProtectModule implements PluginModule {

    private final PermissionGate permissionGate;

    public CrashProtectModule(@NotNull PermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "crashprotect";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(new ItemGuard(plugin, permissionGate), plugin);
        pluginManager.registerEvents(new EntityNameGuard(plugin), plugin);
        pluginManager.registerEvents(new SignContentGuard(plugin, permissionGate), plugin);
        pluginManager.registerEvents(new SpawnerGuard(plugin), plugin);

        new CrashPacketService(plugin, permissionGate).start();
    }
}
