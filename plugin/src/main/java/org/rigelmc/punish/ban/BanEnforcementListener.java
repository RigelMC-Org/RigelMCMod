package org.rigelmc.punish.ban;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.identity.IpHasher;

/**
 * Enforces bans at login (name or IP), not just for currently-online targets when a ban
 * is first issued - without this, {@code /ban}/{@code /tban}/{@code /permban} would only
 * ever remove someone who's already connected, never actually keep them out. Runs on
 * {@link AsyncPlayerPreLoginEvent}, which already executes off the main thread, so the
 * blocking JDBC lookup here doesn't violate CONTRIBUTING.md's main-thread rule (same
 * reasoning as {@code chat.MuteEnforcementListener}).
 *
 * <p>Deliberately <b>fails closed</b> on a database error (denies login with a
 * "try again" message) - the asymmetric opposite of {@code MuteEnforcementListener}'s
 * fail-open choice, because letting a genuinely banned player back onto a Free-OP server
 * during a transient DB hiccup is a much worse outcome than briefly locking out a
 * legitimate player.</p>
 */
public final class BanEnforcementListener implements Listener {

    private final BanService banService;
    private final IpHasher ipHasher;
    private final Logger logger;

    public BanEnforcementListener(
            @NotNull BanService banService, @NotNull IpHasher ipHasher, @NotNull Logger logger) {
        this.banService = banService;
        this.ipHasher = ipHasher;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(@NotNull AsyncPlayerPreLoginEvent event) {
        try {
            long now = System.currentTimeMillis();

            if (banService.isBanned(event.getUniqueId(), now)) {
                deny(event);
                return;
            }

            String ipHash = ipHasher.hash(event.getAddress().getHostAddress());
            if (banService.isIpBanned(ipHash, now)) {
                deny(event);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to check ban status for " + event.getName() + " - denying login", e);
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Could not verify ban status. Please try again in a moment.", NamedTextColor.RED));
        }
    }

    private static void deny(AsyncPlayerPreLoginEvent event) {
        event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                Component.text("You are banned from this server.", NamedTextColor.RED));
    }
}
