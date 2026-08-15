package org.rigelmc.protect.worldedit.extent;

import com.google.common.eventbus.Subscribe;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.protect.area.ProtectAreaService;
import org.rigelmc.protect.worldedit.WorldEditLimitOverrideService;
import org.rigelmc.rank.PermissionGate;
import org.rigelmc.world.SpawnService;
import org.rigelmc.worldedit.WorldEditBridge;

/**
 * Lifecycle for the {@code EditSessionEvent} extent-chain tier of WorldEdit/FAWE
 * protection: polls for a usable WorldEdit-compatible plugin a bounded number of times on
 * enable (same retry-then-give-up shape as {@code crash.packet.CrashPacketService}), then
 * subscribes {@link RigelEditExtentChain#wrap} to {@link WorldEdit#getInstance()}'s own
 * event bus once one is found - matching TFM's real {@code WorldEditHook#register}, which
 * registers its {@code @Subscribe}-annotated listener object the exact same way (Guava's
 * event bus, reflectively discovered, no interface to implement).
 *
 * <p>Command-preprocess-level abuse prevention ({@link
 * org.rigelmc.protect.worldedit.WorldEditAbuseGuard}) and Bukkit-event {@code
 * /protectarea} enforcement ({@code protect.area.AreaProtectionListener}) both keep working
 * regardless of whether this tier ever manages to attach - this is defense-in-depth layered
 * on top of those, not a replacement for either.</p>
 */
public final class WorldEditExtentService {

    private static final int MAX_ATTEMPTS = 15;
    private static final long RETRY_INTERVAL_TICKS = 40L;

    private final RigelMCMod plugin;
    private final ProtectAreaService protectAreaService;
    private final PermissionGate permissionGate;
    private final WorldEditLimitOverrideService limitOverrideService;
    private final SpawnService spawnService;
    private final WorldEditBridge worldEditBridge;
    private int attempts;

    public WorldEditExtentService(
            @NotNull RigelMCMod plugin, @NotNull ProtectAreaService protectAreaService,
            @NotNull PermissionGate permissionGate, @NotNull WorldEditLimitOverrideService limitOverrideService,
            @NotNull SpawnService spawnService) {
        this.plugin = plugin;
        this.protectAreaService = protectAreaService;
        this.permissionGate = permissionGate;
        this.limitOverrideService = limitOverrideService;
        this.spawnService = spawnService;
        this.worldEditBridge = new WorldEditBridge();
    }

    /** Call once from the owning module's {@code registerListeners}. */
    public void start() {
        if (!plugin.rigelConfig().worldEditGuardEnabled()) {
            plugin.getLogger().info("[WorldEditExtentService] WorldEdit guard disabled via config - skipping.");
            return;
        }
        attemptRegister();
    }

    private void attemptRegister() {
        attempts++;
        if (worldEditBridge.isAvailable()) {
            attach();
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            plugin.getLogger()
                    .info("[WorldEditExtentService] Neither WorldEdit nor FAWE is present after waiting - the"
                            + " EditSessionEvent extent-chain tier is disabled (Bukkit-event /protectarea"
                            + " enforcement and command-preprocess abuse checks still work). Install WorldEdit or"
                            + " FAWE to enable this tier.");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::attemptRegister, RETRY_INTERVAL_TICKS);
    }

    private void attach() {
        try {
            WorldEdit.getInstance().getEventBus().register(new Object() {
                @Subscribe
                public void onEditSession(EditSessionEvent event) {
                    RigelEditExtentChain.wrap(
                            event, plugin, protectAreaService, permissionGate, limitOverrideService, spawnService);
                }
            });
            plugin.getLogger()
                    .info("[WorldEditExtentService] Attached to WorldEdit's event bus - per-block /protectarea"
                            + " enforcement and edit-session abuse caps are active.");
        } catch (RuntimeException e) {
            plugin.getLogger()
                    .warning("[WorldEditExtentService] WorldEdit/FAWE was found but attaching to its event bus"
                            + " failed - the extent-chain tier is disabled. Error: " + e);
        }
    }
}
