package org.rigelmc.punish.appeal;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.audit.AuditLogService;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;
import org.rigelmc.discord.DiscordBotManager;
import org.rigelmc.protect.antigrief.PerPlayerRateLimiter;
import org.rigelmc.punish.ban.Ban;
import org.rigelmc.punish.ban.BanDao;

/**
 * The ban-appeal system's core logic - public submission (from {@code appeal.AppealWebServer})
 * through to a staff Approve/Deny decision (from {@code discord.DiscordBotManager}'s button
 * handling). Pure JDBC + plain Java aside from the {@link DiscordBotManager} hand-off, same
 * "unit-testable without a live server" shape as {@code punish.ban.BanService}.
 *
 * <p>Rate-limited per submitter IP hash ({@link PerPlayerRateLimiter}, generalized this
 * session specifically for this use - see its own javadoc) since this is this project's
 * first genuinely public, unauthenticated, write-capable endpoint; nothing else in this
 * codebase has needed IP-based throttling before.</p>
 */
public final class AppealService {

    private final RigelMCMod plugin;
    private final AppealDao appealDao;
    private final BanDao banDao;
    private final PlayerDao playerDao;
    private final AuditLogService auditLogService;
    private final DiscordBotManager discordBotManager;
    private final PerPlayerRateLimiter<String> ipRateLimiter;

    public AppealService(
            @NotNull RigelMCMod plugin, @NotNull AppealDao appealDao, @NotNull BanDao banDao,
            @NotNull PlayerDao playerDao, @NotNull AuditLogService auditLogService,
            @NotNull DiscordBotManager discordBotManager) {
        this.plugin = plugin;
        this.appealDao = appealDao;
        this.banDao = banDao;
        this.playerDao = playerDao;
        this.auditLogService = auditLogService;
        this.discordBotManager = discordBotManager;
        this.ipRateLimiter = new PerPlayerRateLimiter<>(
                Duration.ofMinutes(Math.max(1, plugin.rigelConfig().appealRateLimitWindowMinutes())).toMillis());
    }

    public enum SubmitResult {
        SUBMITTED,
        BAN_NOT_FOUND,
        BAN_NOT_ACTIVE,
        ALREADY_PENDING,
        MESSAGE_INVALID,
        RATE_LIMITED
    }

    public record SubmitOutcome(@NotNull SubmitResult result, @Nullable Appeal appeal) {}

    /**
     * @param banReference whatever the appellant's link pointed at - a {@code /permban}
     *     cascade's {@code caseId}, or a {@code /ban}/{@code /tban} row's own numeric id
     *     as a string (see {@link #resolveBan})
     * @param submitterIpHash the submitter's IP, already hashed the same way {@code
     *     identity.IpHasher} hashes every other stored IP in this project - callers hash
     *     before calling, matching {@code punish.ban.BanService}'s own established
     *     "this service never sees a raw IP" convention
     */
    @NotNull
    public SubmitOutcome submit(
            @NotNull String banReference, @NotNull String message, @Nullable String contact,
            @NotNull String submitterIpHash)
            throws SQLException {
        int maxLength = plugin.rigelConfig().appealMaxMessageLength();
        String trimmed = message.strip();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            return new SubmitOutcome(SubmitResult.MESSAGE_INVALID, null);
        }

        int maxPerWindow = plugin.rigelConfig().appealRateLimitMaxPerWindow();
        if (maxPerWindow > 0) {
            int count = ipRateLimiter.recordAndCount(submitterIpHash, System.currentTimeMillis());
            if (count > maxPerWindow) {
                return new SubmitOutcome(SubmitResult.RATE_LIMITED, null);
            }
        }

        Optional<Ban> ban = resolveBan(banReference);
        if (ban.isEmpty()) {
            return new SubmitOutcome(SubmitResult.BAN_NOT_FOUND, null);
        }
        long now = System.currentTimeMillis();
        if (!ban.get().isCurrentlyInEffect(now)) {
            return new SubmitOutcome(SubmitResult.BAN_NOT_ACTIVE, null);
        }
        if (appealDao.findPendingByBanReference(banReference).isPresent()) {
            return new SubmitOutcome(SubmitResult.ALREADY_PENDING, null);
        }

        Appeal inserted = appealDao.insert(new Appeal(
                0, banReference, ban.get().id(), trimmed, blankToNull(contact), now, submitterIpHash,
                Appeal.Status.PENDING, null, null, null));
        auditLogService.record(
                null, "BAN_APPEAL_SUBMITTED", ban.get().targetUuid(),
                "appealId=" + inserted.id() + " banId=" + ban.get().id());
        discordBotManager.postAppeal(inserted, ban.get());
        return new SubmitOutcome(SubmitResult.SUBMITTED, inserted);
    }

    public enum DecisionResult {
        APPROVED,
        DENIED,
        NOT_FOUND,
        ALREADY_DECIDED,
        BAN_MISSING
    }

    /** Approves: revokes the underlying ban (whole cascade case, if it was one) and records the decision. */
    @NotNull
    public DecisionResult approve(long appealId, @Nullable UUID decidedByUuid) throws SQLException {
        Optional<Appeal> appealOpt = appealDao.findById(appealId);
        if (appealOpt.isEmpty()) {
            return DecisionResult.NOT_FOUND;
        }
        if (!appealOpt.get().isPending()) {
            return DecisionResult.ALREADY_DECIDED;
        }
        Optional<Ban> banOpt = banDao.findById(appealOpt.get().banId());
        if (banOpt.isEmpty()) {
            return DecisionResult.BAN_MISSING;
        }
        Ban ban = banOpt.get();
        long now = System.currentTimeMillis();
        // decide() itself is the race guard (WHERE status = 'PENDING') - the isPending()
        // check above is only a cheap early-out, not relied on for correctness.
        if (!appealDao.decide(appealId, Appeal.Status.APPROVED, decidedByUuid, now)) {
            return DecisionResult.ALREADY_DECIDED;
        }

        // Cascade-aware: a caseId means the appealed ban was one entry in a /permban
        // cascade - revoke the whole case, matching /punish unban -c semantics, not just
        // the single row this appeal happened to reference.
        if (ban.caseId() != null) {
            banDao.revokeCase(ban.caseId(), decidedByUuid, now);
        } else {
            banDao.revoke(ban.id(), decidedByUuid, now);
        }
        auditLogService.record(decidedByUuid, "BAN_APPEAL_APPROVED", ban.targetUuid(), "appealId=" + appealId);
        return DecisionResult.APPROVED;
    }

    /** Denies: no unban, just records the decision. */
    @NotNull
    public DecisionResult deny(long appealId, @Nullable UUID decidedByUuid) throws SQLException {
        Optional<Appeal> appealOpt = appealDao.findById(appealId);
        if (appealOpt.isEmpty()) {
            return DecisionResult.NOT_FOUND;
        }
        if (!appealOpt.get().isPending()) {
            return DecisionResult.ALREADY_DECIDED;
        }
        if (!appealDao.decide(appealId, Appeal.Status.DENIED, decidedByUuid, System.currentTimeMillis())) {
            return DecisionResult.ALREADY_DECIDED;
        }
        auditLogService.record(decidedByUuid, "BAN_APPEAL_DENIED", null, "appealId=" + appealId);
        return DecisionResult.DENIED;
    }

    @NotNull
    public Optional<Appeal> findById(long id) throws SQLException {
        return appealDao.findById(id);
    }

    @NotNull
    public Optional<Ban> findBan(long banId) throws SQLException {
        return banDao.findById(banId);
    }

    public void setDiscordMessageId(long appealId, @NotNull String discordMessageId) throws SQLException {
        appealDao.setDiscordMessageId(appealId, discordMessageId);
    }

    /**
     * Public entry point for {@code appeal.AppealWebServer}'s form page, which needs to
     * show the ban an appeal-in-progress is actually about (reason/type/expiry) without
     * duplicating {@link #resolveBan}'s reference-parsing logic. Doesn't check whether the
     * ban is still active - the form page decides what "not currently active" should look
     * like to a visitor (a courtesy display concern), {@link #submit} is still the one
     * source of truth for whether a submission is actually accepted.
     */
    @NotNull
    public Optional<Ban> findBanByReference(@NotNull String banReference) throws SQLException {
        return resolveBan(banReference);
    }

    /**
     * User-requested simplification: lets a visitor who arrived at the appeal site
     * directly (not via the exact case-reference link on their kick screen) just type
     * their in-game username instead. Resolves name -&gt; UUID -&gt; their current active
     * ban, then hands back the same kind of reference {@link #findBanByReference} accepts
     * (the ban's {@code caseId} if it has one, else its raw numeric id) so {@code
     * appeal.AppealWebServer} can redirect into the exact same form-rendering path either
     * way - one appeal flow, two ways to arrive at it.
     */
    @NotNull
    public Optional<String> findActiveBanReferenceByUsername(@NotNull String username) throws SQLException {
        Optional<PlayerRecord> player = playerDao.findByLastKnownName(username);
        if (player.isEmpty()) {
            return Optional.empty();
        }
        Optional<Ban> ban = banDao.findActiveByUuid(player.get().uuid(), System.currentTimeMillis());
        return ban.map(b -> b.caseId() != null ? b.caseId() : String.valueOf(b.id()));
    }

    /**
     * A reference is either a raw {@code rigel_bans.id} (numeric - what {@code /ban}/
     * {@code /tban} kick screens link to, since those never get a {@code caseId}) or a
     * {@code /permban} cascade's {@code caseId} (a UUID string). Tried in that order since
     * a {@code caseId} is never purely numeric.
     */
    @NotNull
    private Optional<Ban> resolveBan(String banReference) throws SQLException {
        try {
            return banDao.findById(Long.parseLong(banReference));
        } catch (NumberFormatException e) {
            // A case can have multiple linked rows (one per name/IP entry) sharing one
            // expiry/active state by construction - any one of them is representative
            // enough to validate against and to show on the appeal form.
            return banDao.findByCaseId(banReference).stream().findFirst();
        }
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
