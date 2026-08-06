package org.rigelmc.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.core.RigelConfig;

/**
 * Builds the HikariCP-pooled {@link HikariDataSource} for either backend supported by
 * {@code storage.type} in {@code config.yml} - SQLite (default, zero-config) or
 * MySQL/MariaDB. Both HikariCP and the JDBC drivers are resolved at plugin-load time by
 * {@link org.rigelmc.RigelPluginLoader}, not shaded into the jar.
 */
public final class DataSourceFactory {

    private DataSourceFactory() {}

    @NotNull
    public static HikariDataSource create(@NotNull RigelConfig config, @NotNull File dataFolder) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("RigelMCMod-Hikari");

        if (config.isMysqlStorage()) {
            hikariConfig.setJdbcUrl(
                    "jdbc:mariadb://%s:%d/%s?useSsl=%s"
                            .formatted(
                                    config.mysqlHost(), config.mysqlPort(), config.mysqlDatabase(),
                                    config.mysqlUseSsl()));
            hikariConfig.setUsername(config.mysqlUsername());
            hikariConfig.setPassword(config.mysqlPassword());
            hikariConfig.setMaximumPoolSize(10);
        } else {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                throw new IllegalStateException("Could not create plugin data folder: " + dataFolder);
            }
            File dbFile = new File(dataFolder, config.sqliteFileName());
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            // SQLite only supports one writer at a time; a small pool avoids
            // "database is locked" errors under concurrent access better than a single
            // connection would (readers can still proceed while a writer is busy).
            hikariConfig.setMaximumPoolSize(4);
        }

        return new HikariDataSource(hikariConfig);
    }
}
