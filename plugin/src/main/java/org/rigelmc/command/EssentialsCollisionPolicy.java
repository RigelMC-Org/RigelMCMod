package org.rigelmc.command;

import org.rigelmc.core.RigelConfig;

/**
 * Decides whether a RigelMCMod command may additionally register a shorter/aliased
 * top-level name (e.g. {@code /rkick} also as {@code /kick}), per the "Command framework
 * & the Essentials collision fix" design: shadowing is only ever allowed when the
 * operator opted in via {@code aliases.enable-shadowing: true} in {@code config.yml}
 * AND Essentials is not currently enabled - Essentials' presence always wins,
 * regardless of config, since it's the plugin actually meant to own general-purpose
 * command names on a Free-OP server.
 */
public final class EssentialsCollisionPolicy {

    private final boolean shadowingAllowed;

    public EssentialsCollisionPolicy(RigelConfig config, boolean essentialsPresent) {
        boolean configWantsShadowing = config.raw().getBoolean("aliases.enable-shadowing", false);
        this.shadowingAllowed = configWantsShadowing && !essentialsPresent;
    }

    /**
     * @return {@code true} if a module may register a shorter alias for a command whose
     *     primary name is already collision-safe (e.g. registering {@code /kick} as an
     *     extra alias of {@code /punish kick} / {@code /rkick})
     */
    public boolean isShadowingAllowed() {
        return shadowingAllowed;
    }
}
