package org.rigelmc.rank;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.api.rank.RankProvider;

/**
 * Registered with Bukkit's {@code ServicesManager} so other plugins can integrate
 * against {@link RankProvider} - see docs/architecture.md's {@code api/} module design.
 * Prefers {@link PermissionGate}'s in-memory online-rank cache (no I/O); falls back to a
 * blocking {@link RankService} lookup only for offline players, since there's no other
 * way to answer that question - external callers should expect that path to be slower.
 */
public final class RankProviderImpl implements RankProvider {

    private final RankService rankService;
    private final PermissionGate permissionGate;
    private final Logger logger;

    public RankProviderImpl(
            @NotNull RankService rankService, @NotNull PermissionGate permissionGate, @NotNull Logger logger) {
        this.rankService = rankService;
        this.permissionGate = permissionGate;
        this.logger = logger;
    }

    @Override
    public Optional<String> getRankId(UUID uuid) {
        try {
            return Optional.of(rankService.rankOf(uuid).id());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "RankProvider#getRankId failed for " + uuid, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean hasAtLeast(UUID uuid, String rankId) {
        if (permissionGate.hasAtLeastCached(uuid, rankId)) {
            return true;
        }
        try {
            return rankService.hasAtLeast(uuid, rankId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "RankProvider#hasAtLeast failed for " + uuid, e);
            return false;
        }
    }
}
