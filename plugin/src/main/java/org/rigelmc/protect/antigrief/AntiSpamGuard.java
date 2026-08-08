package org.rigelmc.protect.antigrief;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.rank.PermissionGate;

/**
 * Anti-spam: chat/command flood control plus repeated-message detection. Chat is checked on
 * the modern {@link AsyncChatEvent} (already off-thread) at {@code LOW} priority, so a
 * cancellation here happens before {@code chat.ChatFormatListener} ever renders it; commands
 * via {@link PlayerCommandPreprocessEvent}. TFM ref: AntiSpam.java. Reads {@code
 * plugin.rigelConfig()} fresh on every event - see {@link AntiNukeGuard}'s javadoc for why.
 */
public final class AntiSpamGuard implements Listener {

    private final RigelMCMod plugin;
    private final PermissionGate permissionGate;
    private final PerPlayerRateLimiter<UUID> chatMessages = new PerPlayerRateLimiter<>(10_000);
    private final PerPlayerRateLimiter<UUID> commands = new PerPlayerRateLimiter<>(10_000);
    private final Map<UUID, RepeatTracker> repeats = new ConcurrentHashMap<>();

    public AntiSpamGuard(@NotNull RigelMCMod plugin, @NotNull PermissionGate permissionGate) {
        this.plugin = plugin;
        this.permissionGate = permissionGate;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(@NotNull AsyncChatEvent event) {
        if (!plugin.rigelConfig().antiSpamEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());

        RepeatTracker tracker = repeats.computeIfAbsent(player.getUniqueId(), key -> new RepeatTracker());
        if (tracker.recordAndCheck(
                plain, now, plugin.rigelConfig().antiSpamRepeatWindowSeconds() * 1000L,
                plugin.rigelConfig().antiSpamMaxRepeats())) {
            breach(player, "repeated messages");
            event.setCancelled(true);
            return;
        }

        int count = chatMessages.recordAndCount(player.getUniqueId(), now);
        if (count > plugin.rigelConfig().antiSpamMaxChatMessagesPer10s()) {
            breach(player, "chat flood");
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        if (!plugin.rigelConfig().antiSpamEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        int count = commands.recordAndCount(player.getUniqueId(), System.currentTimeMillis());
        if (count > plugin.rigelConfig().antiSpamMaxCommandsPer10s()) {
            breach(player, "command flood");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        chatMessages.clear(uuid);
        commands.clear(uuid);
        repeats.remove(uuid);
    }

    private void breach(Player player, String reason) {
        AntiGriefSupport.applyAction(
                plugin.rigelConfig().antiSpamAction(),
                player,
                permissionGate,
                "[AntiSpam] " + player.getName() + " triggered " + reason + ".",
                "Disconnected: spam detected.");
    }
}
