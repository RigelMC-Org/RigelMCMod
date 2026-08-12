package org.rigelmc.guild.plot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Synchronous JDBC access to {@code rigel_guild_plot_cosmetics} - a purchase receipt per (guild, cosmetic), see {@link PlotCosmetic}'s javadoc. */
public final class PlotCosmeticDao {

    private final DataSource dataSource;

    public PlotCosmeticDao(@NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isPurchased(int guildId, @NotNull String cosmeticKey) throws SQLException {
        String sql = "SELECT 1 FROM rigel_guild_plot_cosmetics WHERE guild_id = ? AND cosmetic_key = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, guildId);
            statement.setString(2, cosmeticKey);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void recordPurchase(int guildId, @NotNull String cosmeticKey, @NotNull UUID purchasedBy, long now) throws SQLException {
        String sql = "INSERT INTO rigel_guild_plot_cosmetics (guild_id, cosmetic_key, purchased_by, purchased_at) VALUES (?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, guildId);
            statement.setString(2, cosmeticKey);
            statement.setString(3, purchasedBy.toString());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    /** @return every cosmetic key this guild has purchased - for {@code /guild plot cosmetic list}'s "owned" markers. */
    @NotNull
    public Set<String> findPurchasedKeys(int guildId) throws SQLException {
        String sql = "SELECT cosmetic_key FROM rigel_guild_plot_cosmetics WHERE guild_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                Set<String> result = new HashSet<>();
                while (rs.next()) {
                    result.add(rs.getString("cosmetic_key"));
                }
                return result;
            }
        }
    }
}
