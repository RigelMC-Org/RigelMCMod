package org.rigelmc.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.core.RigelConfig;

/**
 * Sets the tab-list header/footer (MiniMessage-formatted, {@code {online}}/{@code {max}}
 * placeholders in the footer) - applied on join and refreshed periodically for everyone
 * so the online count in the footer stays current. See {@code config.yml}'s
 * {@code tablist} section.
 *
 * <p>Uses {@code Audience#sendPlayerListHeaderAndFooter(Component, Component)} (a
 * default method Adventure's {@code Audience} interface - {@link Player} implements it
 * transitively) - Component-native, no legacy-string round-trip needed. An earlier
 * version of this class used the deprecated {@code Player#setPlayerListHeaderFooter
 * (String, String)} instead, wrongly believing (from an incomplete bytecode check that
 * only inspected {@code Player.class} directly, missing this inherited default method)
 * that no Component-native setter existed at all - caught by studying how TotalFreedomMod
 * itself does this (its {@code TabList} class), then re-confirmed via {@code javap}
 * against {@code Audience.class} specifically.</p>
 */
public final class TabListBroadcaster {

    public void apply(@NotNull Player player, @NotNull RigelConfig config) {
        Component header = MiniMessage.miniMessage().deserialize(config.tablistHeader());
        String footerRaw = config.tablistFooter()
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()));
        Component footer = MiniMessage.miniMessage().deserialize(footerRaw);
        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    public void applyToAll(@NotNull RigelConfig config) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player, config);
        }
    }
}
