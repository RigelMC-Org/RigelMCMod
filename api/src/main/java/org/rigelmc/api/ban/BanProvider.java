package org.rigelmc.api.ban;

import java.util.UUID;

/**
 * Read-only access to RigelMCMod's ban state, for other plugins to integrate against
 * (e.g. a login gate on a proxy-side plugin wanting to double-check ban status).
 *
 * <p>Obtain an instance via {@code Bukkit.getServicesManager().load(BanProvider.class)}
 * once RigelMCMod has enabled.</p>
 */
public interface BanProvider {

    /**
     * Checks whether a player is currently banned.
     *
     * @param uuid the player's UUID
     * @return {@code true} if this player is currently banned (temp or permanent, not expired)
     */
    boolean isBanned(UUID uuid);

    /**
     * Checks whether an IP address is currently banned.
     *
     * @param ipHash the salted/hashed IP to check (RigelMCMod never exposes raw IPs
     *     through the API; see the Network topology / Ban system design notes)
     * @return {@code true} if this IP is currently banned (temp or permanent, not expired)
     */
    boolean isIpBanned(String ipHash);
}
