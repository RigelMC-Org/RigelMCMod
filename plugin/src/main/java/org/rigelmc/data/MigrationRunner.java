package org.rigelmc.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/**
 * Hand-rolled schema migration runner, applying ordered {@code db/migration/V<n>__*.sql}
 * classpath resources inside a transaction, tracked in a {@code rigel_schema_history}
 * table. Chosen over Flyway per the architecture plan: Flyway's SQLite dialect support
 * has historically been inconsistent, and this is ~50 lines of logic for a two-backend
 * (SQLite/MySQL) project.
 *
 * <p>Migration file names are an explicit ordered list rather than discovered by
 * scanning the classpath - listing files inside a resource "directory" isn't portable
 * between running from an IDE and running from a shaded jar, so new migrations must be
 * added to {@link #MIGRATIONS} as well as to {@code src/main/resources/db/migration/}.</p>
 */
public final class MigrationRunner {

    /** Ordered (version, classpath resource name) pairs. Append, never reorder/remove. */
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "V1__init.sql"),
            new Migration(2, "V2__discord.sql"),
            new Migration(3, "V3__world_state.sql"),
            new Migration(4, "V4__display_names.sql"),
            new Migration(5, "V5__punishment_depth.sql"),
            new Migration(6, "V6__spawn.sql"),
            new Migration(7, "V7__myadmin.sql"),
            new Migration(8, "V8__protect_area.sql"),
            new Migration(9, "V9__flatlands_restart_wipe.sql"),
            new Migration(10, "V10__flatlands_wipe_folder.sql"),
            new Migration(11, "V11__ban_appeals.sql"),
            new Migration(12, "V12__economy.sql"),
            new Migration(13, "V13__guilds.sql"),
            new Migration(14, "V14__guild_plot_cosmetics.sql"),
            new Migration(15, "V15__discord_invite_credits.sql"),
            new Migration(16, "V16__vote_tracking.sql"));

    private final Logger logger;
    private final boolean mysqlDialect;

    public MigrationRunner(@NotNull Logger logger, boolean mysqlDialect) {
        this.logger = logger;
        this.mysqlDialect = mysqlDialect;
    }

    public void migrate(@NotNull DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            int currentVersion = currentVersion(connection);

            for (Migration migration : MIGRATIONS) {
                if (migration.version() <= currentVersion) {
                    continue;
                }
                applyMigration(connection, migration);
            }
        }
    }

    private void ensureHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS rigel_schema_history ("
                            + "version INTEGER NOT NULL PRIMARY KEY, "
                            + "applied_at BIGINT NOT NULL)");
        }
    }

    private int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT MAX(version) FROM rigel_schema_history")) {
            if (resultSet.next()) {
                return resultSet.getInt(1); // 0 if no rows (SQL NULL -> 0 via getInt)
            }
            return 0;
        }
    }

    private void applyMigration(Connection connection, Migration migration) throws SQLException {
        String script = readResource(migration.resourceName());
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sql : splitStatements(script)) {
                statement.execute(dialectAdapt(sql));
            }
            statement.executeUpdate(
                    "INSERT INTO rigel_schema_history (version, applied_at) VALUES ("
                            + migration.version() + ", " + System.currentTimeMillis() + ")");
            connection.commit();
            logger.info("Applied database migration " + migration.resourceName());
        } catch (SQLException e) {
            connection.rollback();
            throw new SQLException("Failed to apply migration " + migration.resourceName(), e);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * The migration scripts are authored against SQLite syntax (the default backend);
     * this rewrites the handful of tokens that differ under MySQL/MariaDB rather than
     * maintaining two parallel copies of every script.
     */
    private String dialectAdapt(String sql) {
        if (!mysqlDialect) {
            return sql;
        }
        return sql.replace("AUTOINCREMENT", "AUTO_INCREMENT");
    }

    /** Strips {@code --} comment lines, then splits the remaining script on {@code ;}. */
    private static List<String> splitStatements(String script) {
        String withoutComments = script.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));

        return java.util.Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String readResource(String name) {
        try (InputStream in = MigrationRunner.class.getClassLoader().getResourceAsStream("db/migration/" + name)) {
            if (in == null) {
                throw new IOException("Migration resource not found on classpath: db/migration/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Migration(int version, String resourceName) {}
}
