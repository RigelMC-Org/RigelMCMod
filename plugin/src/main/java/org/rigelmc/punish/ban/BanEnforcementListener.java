package org.rigelmc.punish.ban;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.core.RigelConfig;
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
 *
 * <p><b>Kick screen detail + appeal link</b> - user-reported gap: this used to show a
 * single bare hardcoded line with no reason, no expiry, and no way to contest the ban.
 * Now resolves the actual matching {@link Ban} row (previously only a {@code boolean}
 * ever got checked) and shows its reason/type/expiry, plus - only if {@code
 * web.appeal.public-url} is configured ({@link RigelConfig#appealPublicUrl}) - a clickable
 * appeal link ({@link ClickEvent#openUrl}) built from the ban's own {@code caseId} (a
 * {@code /permban} cascade) or numeric {@code id} ({@code /ban}/{@code /tban}, which never
 * get a {@code caseId}) - see {@code punish.appeal.AppealService#resolveBan} for the
 * matching lookup on the other end. Never shows a link at all if that config key is blank,
 * rather than ever showing one that goes nowhere real.</p>
 */
public final class BanEnforcementListener implements Listener {

    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final BanService banService;
    private final BanDao banDao;
    private final IpHasher ipHasher;
    private final RigelConfig config;
    private final Logger logger;

    public BanEnforcementListener(
            @NotNull BanService banService, @NotNull BanDao banDao, @NotNull IpHasher ipHasher,
            @NotNull RigelConfig config, @NotNull Logger logger) {
        this.banService = banService;
        this.banDao = banDao;
        this.ipHasher = ipHasher;
        this.config = config;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(@NotNull AsyncPlayerPreLoginEvent event) {
        try {
            long now = System.currentTimeMillis();

            if (banService.isBanned(event.getUniqueId(), now)) {
                banDao.findActiveByUuid(event.getUniqueId(), now).ifPresentOrElse(
                        ban -> deny(event, ban), () -> deny(event, null));
                return;
            }

            String ipHash = ipHasher.hash(event.getAddress().getHostAddress());
            if (banService.isIpBanned(ipHash, now)) {
                banDao.findActiveByIp(ipHash, now).ifPresentOrElse(ban -> deny(event, ban), () -> deny(event, null));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to check ban status for " + event.getName() + " - denying login", e);
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Could not verify ban status. Please try again in a moment.", NamedTextColor.RED));
        }
    }

    /** {@code ban} is {@code null} only in the unlikely event the boolean check and the row lookup raced apart. */
    private void deny(AsyncPlayerPreLoginEvent event, @Nullable Ban ban) {
        Component message = ban == null
                ? Component.text("You are banned from this server.", NamedTextColor.RED)
                : buildKickMessage(ban);
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, message);
    }

    @NotNull
    private Component buildKickMessage(@NotNull Ban ban) {
        Component message = Component.text("You are banned from this server.", NamedTextColor.RED)
                .appendNewline()
                .append(Component.text("Reason: " + ban.reason(), NamedTextColor.GRAY))
                .appendNewline()
                .append(Component.text(
                        ban.isPermanent()
                                ? "This ban is permanent."
                                : "Expires: " + EXPIRY_FORMAT.format(Instant.ofEpochMilli(ban.expiresAt())),
                        NamedTextColor.GRAY));

        String appealUrl = appealUrlFor(ban);
        if (appealUrl != null) {
            message = message
                    .appendNewline()
                    .append(Component.text("Appeal at: ", NamedTextColor.GRAY))
                    .append(Component.text(appealUrl, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(appealUrl)));
        }
        return message;
    }

    @Nullable
    private String appealUrlFor(@NotNull Ban ban) {
        String base = config.appealPublicUrl();
        if (base.isBlank()) {
            return null;
        }
        String reference = ban.caseId() != null ? ban.caseId() : String.valueOf(ban.id());
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + "case=" + reference;
    }
}
