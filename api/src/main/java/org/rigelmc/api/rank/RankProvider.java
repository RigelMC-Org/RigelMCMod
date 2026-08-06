package org.rigelmc.api.rank;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to a player's RigelMCMod rank, for other plugins to integrate against.
 *
 * <p>Obtain an instance via {@code Bukkit.getServicesManager().load(RankProvider.class)}
 * once RigelMCMod has enabled.</p>
 */
public interface RankProvider {

    /**
     * Looks up the rank id currently held by the given player.
     *
     * @param uuid the player's UUID
     * @return the rank id (e.g. {@code "admin"}, {@code "senior_admin"}), or empty if the
     *     player is unranked / unknown
     */
    Optional<String> getRankId(UUID uuid);

    /**
     * Returns whether the given player's rank is at least as senior as {@code rankId}
     * in the rank ladder (by weight).
     *
     * @param uuid the player's UUID
     * @param rankId the rank id to compare against
     * @return {@code true} if the player's rank weight is greater than or equal to the
     *     weight of {@code rankId}
     */
    boolean hasAtLeast(UUID uuid, String rankId);
}
