package org.rigelmc.appeal;

import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.identity.IpHasher;
import org.rigelmc.punish.appeal.AppealService;

/**
 * The public ban-appeal form - off by default ({@code modules.appeal.enabled: false}, see
 * {@code RigelConfig#isModuleEnabled}'s webpanel/appeal special-case), since it stands up
 * its own network-facing {@link AppealWebServer}. See that class's javadoc for why it's a
 * separate {@code HttpServer} instance from {@code webpanel.WebPanelServer} rather than
 * folded into it.
 */
public final class AppealModule implements PluginModule {

    private final AppealService appealService;
    private final IpHasher ipHasher;
    private AppealWebServer server;

    public AppealModule(@NotNull AppealService appealService, @NotNull IpHasher ipHasher) {
        this.appealService = appealService;
        this.ipHasher = ipHasher;
    }

    @Override
    public String id() {
        return "appeal";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        server = new AppealWebServer(plugin, appealService, ipHasher);
        server.start();
    }
}
