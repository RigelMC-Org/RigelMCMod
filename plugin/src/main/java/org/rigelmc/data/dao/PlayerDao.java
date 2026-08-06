package org.rigelmc.data.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.identity.PlayerIdentity;

/**
 * Synchronous JDBC access to {@code rigel_players}. Every method here runs blocking I/O -
 * callers (services) are responsible for dispatching onto {@link org.rigelmc.data.RigelExecutors}
 * and never calling these directly from the main thread.
 */
public final class PlayerDao {

    private final DataSource dataSource;

    public PlayerDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Records a login: inserts a new player row (defaulting to the {@code default} rank)
     * or updates the existing row's last-known name/identity/last-seen timestamp.
     */
    public void upsertOnLogin(UUID uuid, String name, PlayerIdentity identity, long nowEpochMillis)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select =
                        connection.prepareStatement("SELECT 1 FROM rigel_players WHERE uuid = ?")) {
                    select.setString(1, uuid.toString());
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }

                if (exists) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE rigel_players SET last_known_name = ?, identity_type = ?,"
                                    + " last_seen_at = ? WHERE uuid = ?")) {
                        update.setString(1, name);
                        update.setString(2, identity.name());
                        update.setLong(3, nowEpochMillis);
                        update.setString(4, uuid.toString());
                        update.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO rigel_players"
                                    + " (uuid, last_known_name, identity_type, rank_id, first_seen_at,"
                                    + " last_seen_at) VALUES (?, ?, ?, 'default', ?, ?)")) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, name);
                        insert.setString(3, identity.name());
                        insert.setLong(4, nowEpochMillis);
                        insert.setLong(5, nowEpochMillis);
                        insert.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @NotNull
    public Optional<PlayerRecord> findByUuid(UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT uuid, last_known_name, identity_type, rank_id, first_seen_at,"
                                + " last_seen_at FROM rigel_players WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Resolves a player by their last-known name (case-insensitive), for commands that
     * take an offline player name such as {@code /ban}/{@code /permban}.
     *
     * <p>More than one row can legitimately share a {@code last_known_name}: this table is
     * keyed by UUID, not name, and the same physical player can resolve to different UUIDs
     * across sessions (proxy/Eaglercraft-bridge UUID differences, online-mode toggles,
     * etc. - see {@code rank.RankAdminModule}'s duplicate-admin bug postmortem). When that
     * happens, this deterministically picks whichever matching row was seen most
     * recently ({@code ORDER BY last_seen_at DESC}) rather than an arbitrary one - the
     * least-surprising choice, and self-correcting over time as stale rows stop being the
     * most-recently-seen match. Callers that need to know about the ambiguity itself
     * (to warn an operator) should also check {@link #countByLastKnownName}.</p>
     */
    @NotNull
    public Optional<PlayerRecord> findByLastKnownName(String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT uuid, last_known_name, identity_type, rank_id, first_seen_at,"
                                + " last_seen_at FROM rigel_players WHERE LOWER(last_known_name) ="
                                + " LOWER(?) ORDER BY last_seen_at DESC LIMIT 1")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /**
     * @return how many distinct {@code rigel_players} rows currently share this
     *     last-known name (case-insensitive) - {@code > 1} means {@link
     *     #findByLastKnownName} is resolving an ambiguous name, see that method's javadoc.
     */
    public int countByLastKnownName(String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM rigel_players WHERE LOWER(last_known_name) = LOWER(?)")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void setRank(UUID uuid, String rankId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("UPDATE rigel_players SET rank_id = ? WHERE uuid = ?")) {
            statement.setString(1, rankId);
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        }
    }

    /**
     * @return every player currently holding a non-default rank, ordered by rank weight
     *     descending then name - for {@code /adminconfig list} and the web panel.
     */
    @NotNull
    public List<PlayerRecord> findAllRanked() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT p.uuid, p.last_known_name, p.identity_type, p.rank_id, p.first_seen_at,"
                                + " p.last_seen_at FROM rigel_players p"
                                + " JOIN rigel_ranks r ON r.rank_id = p.rank_id"
                                + " WHERE p.rank_id <> 'default'"
                                + " ORDER BY r.weight DESC, p.last_known_name ASC")) {
            try (ResultSet rs = statement.executeQuery()) {
                List<PlayerRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        }
    }

    /**
     * {@code /adminconfig reset confirm} - resets every non-default-ranked player back to
     * the default rank in a single bulk statement. Destructive, console-confirmed only -
     * see {@code rank.RankAdminModule}.
     *
     * @return the number of rows affected
     */
    public int resetAllRanks() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE rigel_players SET rank_id = 'default' WHERE rank_id <> 'default'")) {
            return statement.executeUpdate();
        }
    }

    public boolean anyPlayersExist() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT 1 FROM rigel_players LIMIT 1");
                ResultSet rs = statement.executeQuery()) {
            return rs.next();
        }
    }

    private static PlayerRecord mapRow(ResultSet rs) throws SQLException {
        return new PlayerRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("last_known_name"),
                PlayerIdentity.valueOf(rs.getString("identity_type")),
                rs.getString("rank_id"),
                rs.getLong("first_seen_at"),
                rs.getLong("last_seen_at"));
    }
}
