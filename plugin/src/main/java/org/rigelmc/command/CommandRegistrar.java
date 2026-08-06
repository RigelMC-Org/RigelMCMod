package org.rigelmc.command;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import org.bukkit.Bukkit;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;

/**
 * Single {@link LifecycleEvents#COMMANDS} handler that sweeps every enabled
 * {@link PluginModule} and lets it contribute its own Brigadier commands - see the
 * "Command framework & the Essentials collision fix" section of the project's
 * architecture plan for the full design (consolidated command roots, default-safe
 * naming, config-gated aliasing).
 *
 * <p>{@link EssentialsCollisionPolicy} is consulted here too: since Essentials is
 * declared as a {@code load: BEFORE} soft dependency in {@code paper-plugin.yml}, this
 * handler runs after Essentials has already enabled, so presence can be detected
 * reliably before any alias registration decisions are made.</p>
 */
public final class CommandRegistrar {

    private final RigelMCMod plugin;
    private final List<PluginModule> modules;
    private final RigelConfig config;

    public CommandRegistrar(RigelMCMod plugin, List<PluginModule> modules, RigelConfig config) {
        this.plugin = plugin;
        this.modules = modules;
        this.config = config;
    }

    public void registerLifecycleHandler() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            boolean essentialsPresent = Bukkit.getPluginManager().isPluginEnabled("Essentials");
            EssentialsCollisionPolicy collisionPolicy = new EssentialsCollisionPolicy(config, essentialsPresent);
            if (essentialsPresent) {
                plugin.getLogger()
                        .info("Essentials detected - command-alias shadowing forced off regardless of config"
                                + " (see EssentialsCollisionPolicy).");
            }

            for (PluginModule module : modules) {
                if (module.isEnabled(config)) {
                    module.contributeCommands(registrar, collisionPolicy);
                }
            }
        });
    }
}
