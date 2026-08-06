package org.rigelmc.data.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/**
 * JDBC access to {@code rigel_ip_history} - full login history per player (not just a
 * single last-known IP), recorded on every login for moderation visibility and to power
 * {@code /permban}'s cascading name&lt;-&gt;IP resolution in both directions. See the
 * "Network topology & multi-client identity" and "Ban system" sections of
 * docs/architecture.md for why full history (not last-known-only) matters: offline/
 * Eaglercraft identities can trivially regenerate their UUID by picking a new username,
 * so catching every name that ever shared an IP is the actual anti-evasion mechanism.
 */
public final class IpHistoryDao {

    private final DataSource dataSource;

    public IpHistoryDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Records (or bumps the last-seen timestamp of) one uuid/ip_hash login pairing. */
    public void recordSeen(UUID uuid, String ipHash, long nowEpochMillis) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT 1 FROM rigel_ip_history WHERE uuid = ? AND ip_hash = ?")) {
                    select.setString(1, uuid.toString());
                    select.setString(2, ipHash);
                    try (ResultSet rs = select.executeQuery()) {
                        exists = rs.next();
                    }
                }

                if (exists) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE rigel_ip_history SET last_seen_at = ? WHERE uuid = ? AND ip_hash = ?")) {
                        update.setLong(1, nowEpochMillis);
                        update.setString(2, uuid.toString());
                        update.setString(3, ipHash);
                        update.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO rigel_ip_history (uuid, ip_hash, first_seen_at, last_seen_at)"
                                    + " VALUES (?, ?, ?, ?)")) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, ipHash);
                        insert.setLong(3, nowEpochMillis);
                        insert.setLong(4, nowEpochMillis);
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

    /** @return every IP hash this UUID has ever logged in from, most-recent first. */
    @NotNull
    public Set<String> findIpsForUuid(UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT ip_hash FROM rigel_ip_history WHERE uuid = ? ORDER BY last_seen_at DESC")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                Set<String> result = new LinkedHashSet<>();
                while (rs.next()) {
                    result.add(rs.getString("ip_hash"));
                }
                return result;
            }
        }
    }

    /** @return every UUID that has ever logged in from this IP hash, most-recent first. */
    @NotNull
    public Set<UUID> findUuidsForIp(String ipHash) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT uuid FROM rigel_ip_history WHERE ip_hash = ? ORDER BY last_seen_at DESC")) {
            statement.setString(1, ipHash);
            try (ResultSet rs = statement.executeQuery()) {
                Set<UUID> result = new LinkedHashSet<>();
                while (rs.next()) {
                    result.add(UUID.fromString(rs.getString("uuid")));
                }
                return result;
            }
        }
    }
}
