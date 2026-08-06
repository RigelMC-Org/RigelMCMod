package org.rigelmc.skin;

import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;

/**
 * Thin shell around {@link SkinBridge} - see that class's own javadoc for why this
 * currently does nothing beyond presence detection ({@code /skin} stays entirely
 * SkinsRestorer's own command). Exists as a real {@link PluginModule} rather than a bare
 * static bridge purely so {@code modules.skin.enabled: false} can suppress even the
 * presence-detection log line, matching every other module's on/off contract in this
 * codebase - a real, if currently minimal, home for the policy layer described in {@link
 * SkinBridge}'s javadoc once one actually gets built.
 */
public final class SkinModule implements PluginModule {

    @Override
    public String id() {
        return "skin";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        if (new SkinBridge().isAvailable()) {
            plugin.getLogger().info("SkinsRestorer detected.");
        }
    }
}
