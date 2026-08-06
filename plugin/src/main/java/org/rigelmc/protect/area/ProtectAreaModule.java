package org.rigelmc.protect.area;

import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * {@code /protectarea} (aliases {@code protectregion}, {@code protect}) - a DB-backed
 * region-protection system built to genuinely improve on TFM's real {@code ProtectArea}
 * (studied directly from its production source), not just replicate it: a per-region
 * owner/member bypass list (TFM's model is purely binary - any admin bypasses every region,
 * with no other granularity at all), six real per-region flags (TFM only has three
 * <i>global</i> toggles applying to every region or none), an overlap/priority policy (TFM
 * allows silent, unranked overlap), boundary visualization and paginated listing (TFM has
 * neither), and one closed coverage gap ({@code InventoryMoveItemEvent} - see {@link
 * AreaProtectionListener}'s javadoc).
 *
 * <p>Deliberately scoped to Bukkit-event enforcement only (this phase) - WorldEdit/FAWE's
 * own block-writing API doesn't fire standard Bukkit block events at all, so stopping a
 * WorldEdit edit inside a protected region needs a real compile-time WorldEdit/FAWE
 * dependency and an {@code EditSessionEvent} extent chain, which is a separate, deliberately
 * deferred follow-up (see {@code protect.worldedit.WorldEditProtectModule}'s javadoc) - not
 * verifiable in a sandbox with no live server or WorldEdit/FAWE jar to test against. Bukkit-
 * event protection alone already covers the dominant grief vector (a player without
 * WorldEdit access).</p>
 */
public final class ProtectAreaModule implements PluginModule {

    private final ProtectAreaService service;
    private final ProtectAreaCommand command;

    public ProtectAreaModule(
            @NotNull PermissionGate permissionGate, @NotNull ProtectAreaService service,
            @NotNull AuditLogService auditLogService, @NotNull ExecutorService dbExecutor) {
        this.service = service;
        this.command = new ProtectAreaCommand(service, permissionGate, auditLogService, dbExecutor);
    }

    @Override
    public String id() {
        return "protectarea";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        command.bind(plugin);
        try {
            service.loadPersistedState();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load persisted /protectarea state", e);
        }

        AreaProtectionListener listener = new AreaProtectionListener(service);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);

        // Matches TFM's own periodic stray-item sweep (its ITEM_SWEEP_RATE, 40 ticks).
        Bukkit.getScheduler().runTaskTimer(plugin, listener::sweepStrayItems, 40L, 40L);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(command.build("protectarea"), "Manage protected regions - Moderator+",
                List.of("protectregion", "protect"));
    }
}
