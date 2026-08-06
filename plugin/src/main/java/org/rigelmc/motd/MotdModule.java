package org.rigelmc.motd;

import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;

/** Wraps {@link MotdListener} as a toggleable module. */
public final class MotdModule implements PluginModule {

    @Override
    public String id() {
        return "motd";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        plugin.getServer().getPluginManager().registerEvents(new MotdListener(plugin), plugin);
    }
}
