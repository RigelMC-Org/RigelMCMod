package org.rigelmc.protect.worldedit;

import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.protect.area.ProtectAreaService;
import org.rigelmc.protect.worldedit.extent.WorldEditExtentService;
import org.rigelmc.punish.warn.StrikeService;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.worldedit.WorldEditBridge;

/**
 * WorldEdit/FAWE integration, two tiers: the command-preprocess-level abuse prevention
 * layer ({@link WorldEditAbuseGuard}, always registered), and the real {@code
 * EditSessionEvent} extent-chain tier ({@link WorldEditExtentService}, attaches only once a
 * usable WorldEdit-compatible plugin is actually present) that gives {@code /protectarea}
 * (see {@code protect.area.ProtectAreaModule}) per-block enforcement against a live
 * WorldEdit/FAWE edit, plus the selection-volume/container/blocked-type caps the
 * command-preprocess layer alone can only approximate via string matching. Always
 * registers the command-preprocess listener - cheap no-op per command when neither
 * WorldEdit nor FAWE is installed, since {@link WorldEditAbuseGuard} only ever matches
 * commands actually owned by one of those plugins - rather than gating registration on
 * {@link WorldEditBridge#isAvailable()} at enable time, so this keeps working correctly if
 * WorldEdit/FAWE gets installed onto an already-running server; the extent-chain tier does
 * its own bounded polling for exactly that reason (see {@link WorldEditExtentService}).
 */
public final class WorldEditProtectModule implements PluginModule {

    private final PermissionGate permissionGate;
    private final StrikeService strikeService;
    private final ProtectAreaService protectAreaService;

    public WorldEditProtectModule(
            @NotNull PermissionGate permissionGate, @NotNull StrikeService strikeService,
            @NotNull ProtectAreaService protectAreaService) {
        this.permissionGate = permissionGate;
        this.strikeService = strikeService;
        this.protectAreaService = protectAreaService;
    }

    @Override
    public String id() {
        return "worldeditprotect";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new WorldEditAbuseGuard(plugin, permissionGate, strikeService), plugin);
        new WorldEditExtentService(plugin, protectAreaService, permissionGate).start();
    }
}
