package org.rigelmc;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

/**
 * Paper plugin bootstrapper. Runs before the server world/plugin-manager is fully up,
 * giving a hook to do early setup (e.g. constructor-injecting values into
 * {@link RigelMCMod}) as the project grows. Phase 0 has nothing to do here yet -
 * {@link PluginBootstrap#createPlugin} falls back to its default reflective
 * instantiation of {@link RigelMCMod}.
 */
public final class RigelBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLogger().info("RigelMCMod bootstrapping...");
    }
}
