package org.rigelmc.punish.ban;

import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.api.ban.BanProvider;

/**
 * Registered with Bukkit's {@code ServicesManager} so other plugins can integrate
 * against {@link BanProvider} - see docs/architecture.md's {@code api/} module design.
 * Both methods are blocking JDBC calls; this is a read-only, occasional-use API, not a
 * hot-path check, so that tradeoff is acceptable and documented on the interface itself.
 */
public final class BanProviderImpl implements BanProvider {

    private final BanService banService;
    private final Logger logger;

    public BanProviderImpl(@NotNull BanService banService, @NotNull Logger logger) {
        this.banService = banService;
        this.logger = logger;
    }

    @Override
    public boolean isBanned(UUID uuid) {
        try {
            return banService.isBanned(uuid, System.currentTimeMillis());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "BanProvider#isBanned failed for " + uuid, e);
            return false;
        }
    }

    @Override
    public boolean isIpBanned(String ipHash) {
        try {
            return banService.isIpBanned(ipHash, System.currentTimeMillis());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "BanProvider#isIpBanned failed for " + ipHash, e);
            return false;
        }
    }
}
