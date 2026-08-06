package org.rigelmc.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Test helper: a real (temp-file-backed) SQLite database with migrations applied,
 * shared by every pure-JDBC service test - see docs/architecture.md's "no MockBukkit
 * needed for the data layer" testing philosophy.
 */
public final class TestDatabase {

    private TestDatabase() {}

    public static HikariDataSource create(File tempDir) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + new File(tempDir, "test.db").getAbsolutePath());
        config.setMaximumPoolSize(2);
        HikariDataSource dataSource = new HikariDataSource(config);
        new MigrationRunner(Logger.getLogger("TestDatabase"), false).migrate(dataSource);
        return dataSource;
    }
}
