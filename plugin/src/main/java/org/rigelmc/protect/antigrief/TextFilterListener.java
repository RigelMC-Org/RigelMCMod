package org.rigelmc.protect.antigrief;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.punish.ban.BanService;

/**
 * Regex-based prohibited-content filter over chat and commands - off by default, no
 * canonical word list is bundled (see {@code chat.ImpersonationGuard}'s javadoc for the
 * same "not a general profanity filter" stance; operators supply their own patterns). A
 * match auto-{@code /tban}s the sender across their tracked identity via the existing
 * {@link BanService} - reused, not duplicated. TFM ref: TextFilterService.java. Reads
 * {@code plugin.rigelConfig()} fresh on every event - see {@code AntiNukeGuard}'s javadoc
 * for why.
 */
public final class TextFilterListener implements Listener {

    private final RigelMCMod plugin;
    private final BanService banService;
    private final ExecutorService dbExecutor;
    private final Logger logger;

    public TextFilterListener(
            @NotNull RigelMCMod plugin,
            @NotNull BanService banService,
            @NotNull ExecutorService dbExecutor,
            @NotNull Logger logger) {
        this.plugin = plugin;
        this.banService = banService;
        this.dbExecutor = dbExecutor;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(@NotNull AsyncChatEvent event) {
        if (!plugin.rigelConfig().textFilterEnabled()) {
            return;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (matchesAnyPattern(plain)) {
            event.setCancelled(true);
            autoTban(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        if (!plugin.rigelConfig().textFilterEnabled()) {
            return;
        }
        if (matchesAnyPattern(event.getMessage())) {
            event.setCancelled(true);
            autoTban(event.getPlayer());
        }
    }

    private boolean matchesAnyPattern(String text) {
        for (String rawPattern : plugin.rigelConfig().textFilterPatterns()) {
            try {
                if (Pattern.compile(rawPattern, Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                    return true;
                }
            } catch (PatternSyntaxException e) {
                logger.warning("Invalid protect.anti-grief.text-filter pattern '" + rawPattern + "': " + e.getMessage());
            }
        }
        return false;
    }

    private void autoTban(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Duration duration = plugin.rigelConfig().textFilterBanDuration();
        dbExecutor.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                banService.tempBanByName(uuid, name, "Automatic: prohibited content", null, now, now + duration.toMillis());
                Bukkit.getScheduler().runTask(plugin, () -> player.kick(Component.text(
                        "You have been banned for " + duration.toHours() + " hours.\nReason: prohibited content",
                        NamedTextColor.RED)));
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to auto-tban " + name + " for a text-filter match", e);
            }
        });
    }
}
