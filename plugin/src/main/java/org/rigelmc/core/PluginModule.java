package org.rigelmc.core;

import io.papermc.paper.command.brigadier.Commands;
import org.rigelmc.RigelMCMod;
import org.rigelmc.command.EssentialsCollisionPolicy;

/**
 * A self-contained, independently config-toggleable feature area (rank, punish, protect,
 * world, fun, ...). Every feature module implements this so disabled modules never
 * register listeners or contribute commands - the concrete mechanism behind the plugin's
 * "modular, config-driven feature toggles" design goal.
 */
public interface PluginModule {

    /**
     * @return this module's stable config/log identifier, e.g. {@code "punish"}, {@code "fun"}
     */
    String id();

    /**
     * @param config the loaded plugin configuration
     * @return whether this module should be active for the current config
     */
    boolean isEnabled(RigelConfig config);

    /**
     * Registers this module's Bukkit event listeners. Only called when {@link #isEnabled}
     * returned {@code true}.
     *
     * @param plugin the owning plugin instance
     */
    void registerListeners(RigelMCMod plugin);

    /**
     * Registers this module's Brigadier commands. Only called when {@link #isEnabled}
     * returned {@code true}. Default no-op for modules that contribute no commands.
     *
     * @param registrar the Paper Brigadier command registrar
     */
    default void contributeCommands(Commands registrar) {
        // no commands by default
    }

    /**
     * Same as {@link #contributeCommands(Commands)}, but also given the resolved
     * {@link EssentialsCollisionPolicy} - for the few modules that conditionally
     * register an Essentials-name-shadowing alias command (e.g. {@code /mute} as a
     * shorthand for {@code /punish mute} - see {@code PunishModule}). Defaults to
     * delegating to the single-arg overload for every module that doesn't need this, so
     * this is a purely additive, opt-in extension point.
     *
     * @param registrar the Paper Brigadier command registrar
     * @param collisionPolicy whether Essentials-name-shadowing aliases may be registered
     */
    default void contributeCommands(Commands registrar, EssentialsCollisionPolicy collisionPolicy) {
        contributeCommands(registrar);
    }

    /**
     * Called after {@code /rmcm reload} rebuilds {@link RigelConfig} from a re-read
     * {@code config.yml}, for the modules whose in-memory state needs to be explicitly
     * recomputed to pick up new values (e.g. {@code ProtectModule}'s
     * {@code protect.command-access} rules) rather than being read fresh from
     * {@link RigelConfig} on every use already. Only called for currently-enabled
     * modules; toggling {@code modules.*.enabled} itself still requires a restart - see
     * {@code rmcm.RmcmModule}'s reload command for why. Default no-op.
     *
     * @param config the freshly-rebuilt configuration
     */
    default void onConfigReload(RigelConfig config) {
        // no-op by default
    }
}
