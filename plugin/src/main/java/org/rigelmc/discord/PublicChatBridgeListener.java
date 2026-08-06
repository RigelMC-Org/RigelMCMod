package org.rigelmc.discord;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * Relays in-game public chat into the Discord public channel. Runs at
 * {@link EventPriority#MONITOR} with {@code ignoreCancelled = true} so a message already
 * cancelled by {@code chat.MuteEnforcementListener} (or anything else) never gets
 * mirrored to Discord.
 */
public final class PublicChatBridgeListener implements Listener {

    private final DiscordBotManager botManager;

    public PublicChatBridgeListener(@NotNull DiscordBotManager botManager) {
        this.botManager = botManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(@NotNull AsyncChatEvent event) {
        if (!botManager.isReady()) {
            return;
        }
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        botManager.relayPublicMessage(event.getPlayer().getName(), plainMessage);
    }
}
