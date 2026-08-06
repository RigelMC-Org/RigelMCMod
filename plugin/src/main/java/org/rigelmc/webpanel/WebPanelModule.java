package org.rigelmc.webpanel;

import java.util.concurrent.ExecutorService;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.punish.ban.BanDao;
import org.rigelmc.punish.mute.MuteDao;
import org.rigelmc.rank.PermissionGate;

/**
 * The read-only web dashboard - off by default ({@code modules.webpanel.enabled: false},
 * see {@code RigelConfig#isModuleEnabled}'s webpanel special-case). See {@link
 * WebPanelServer}'s javadoc for the full "read-only, zero new dependency, JDK built-in
 * HttpServer" design rationale.
 */
public final class WebPanelModule implements PluginModule {

    private final PlayerDao playerDao;
    private final BanDao banDao;
    private final MuteDao muteDao;
    private final PermissionGate permissionGate;
    private final ExecutorService dbExecutor;
    private WebPanelServer server;

    public WebPanelModule(
            @NotNull PlayerDao playerDao,
            @NotNull BanDao banDao,
            @NotNull MuteDao muteDao,
            @NotNull PermissionGate permissionGate,
            @NotNull ExecutorService dbExecutor) {
        this.playerDao = playerDao;
        this.banDao = banDao;
        this.muteDao = muteDao;
        this.permissionGate = permissionGate;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String id() {
        return "webpanel";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        WebPanelSnapshotService snapshotService =
                new WebPanelSnapshotService(plugin, playerDao, banDao, muteDao, permissionGate, dbExecutor);
        server = new WebPanelServer(plugin, snapshotService);
        server.start();

        long refreshTicks = Math.max(1, plugin.rigelConfig().webPanelRefreshSeconds()) * 20L;
        snapshotService.refresh(); // populate immediately rather than waiting for the first interval
        Bukkit.getScheduler().runTaskTimer(plugin, snapshotService::refresh, refreshTicks, refreshTicks);
    }
}
