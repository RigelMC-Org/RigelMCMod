package org.rigelmc.protect.antigrief;

import java.util.concurrent.ExecutorService;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.punish.ban.BanService;
import org.rigelmc.rank.PermissionGate;

/**
 * Wires together every anti-grief primitive in this package - anti-nuke, anti-spam,
 * anti-drop, projectile-launch rate limiting, TNT/fire block-damage toggles, leaf-decay
 * toggle, the movement validator/world border, gamerule/gamemode-flood enforcement,
 * mob-spam blocking (including the server-wide natural-spawn disable), lethal-potion
 * blocking, and the opt-in text filter. Each guard independently checks its own {@code
 * protect.anti-grief.*} enabled flag on every event, so toggling one via {@code /rmcm
 * reload} takes effect immediately without touching the others. See config.yml's {@code
 * protect.anti-grief} section for the full rationale and every knob.
 */
public final class AntiGriefModule implements PluginModule {

    private final PermissionGate permissionGate;
    private final BanService banService;
    private final ExecutorService dbExecutor;

    public AntiGriefModule(
            @NotNull PermissionGate permissionGate, @NotNull BanService banService, @NotNull ExecutorService dbExecutor) {
        this.permissionGate = permissionGate;
        this.banService = banService;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String id() {
        return "antigrief";
    }

    @Override
    public boolean isEnabled(RigelConfig cfg) {
        return cfg.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        PluginManager pluginManager = plugin.getServer().getPluginManager();

        pluginManager.registerEvents(new AntiNukeGuard(plugin, permissionGate), plugin);
        pluginManager.registerEvents(new AntiSpamGuard(plugin, permissionGate), plugin);
        pluginManager.registerEvents(new AntiDropGuard(plugin), plugin);
        pluginManager.registerEvents(new ProjectileSpamGuard(plugin, permissionGate), plugin);
        pluginManager.registerEvents(new ExplosionAndFireGuard(plugin), plugin);
        pluginManager.registerEvents(new LeafDecayGuard(plugin), plugin);
        pluginManager.registerEvents(new MobSpamGuard(plugin), plugin);
        pluginManager.registerEvents(new PotionGuard(plugin), plugin);
        pluginManager.registerEvents(new TextFilterListener(plugin, banService, dbExecutor, plugin.getLogger()), plugin);

        MovementBoundsGuard movementBoundsGuard = new MovementBoundsGuard(plugin, permissionGate);
        pluginManager.registerEvents(movementBoundsGuard, plugin);
        movementBoundsGuard.applyToLoadedWorlds(plugin.getServer().getWorlds());

        GameplayGuard gameplayGuard = new GameplayGuard(plugin, permissionGate);
        pluginManager.registerEvents(gameplayGuard, plugin);
        gameplayGuard.scheduleEnforcement();
    }
}
