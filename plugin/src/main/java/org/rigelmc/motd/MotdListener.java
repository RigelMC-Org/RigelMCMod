package org.rigelmc.motd;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;

/**
 * TFM-style MOTD customization (TFM's {@code ServerPing}/{@code Command_status}) -
 * picks a random entry from {@code motd.entries} per ping when more than one is
 * configured. Registered on the base {@link ServerListPingEvent} rather than Paper's
 * {@code PaperServerListPingEvent} subclass deliberately: it's the longest-stable,
 * best-documented API for this, and Paper's plugin manager dispatches subclass events to
 * superclass-typed listeners too, so this still fires correctly. Uses the modern
 * Adventure-native {@code motd(Component)} setter (confirmed via Paper's 26.1.2 javadoc -
 * {@code setMotd(String)} is deprecated in its favor), so MiniMessage colors/formatting
 * make it through untranslated rather than needing a legacy-string round-trip.
 *
 * <p>Ping events can fire off the main thread under load (rapid concurrent ping
 * requests) - this handler only does cheap in-memory config reads and Component
 * construction, no blocking I/O, so that's safe.</p>
 */
public final class MotdListener implements Listener {

    private final RigelMCMod plugin;

    public MotdListener(@NotNull RigelMCMod plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPing(@NotNull ServerListPingEvent event) {
        if (!plugin.rigelConfig().motdEnabled()) {
            return;
        }
        List<String> entries = plugin.rigelConfig().motdEntries();
        if (entries.isEmpty()) {
            return;
        }
        String chosen = entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
        Component component = MiniMessage.miniMessage().deserialize(chosen);
        event.motd(component);
    }
}
