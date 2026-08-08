package org.rigelmc.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired whenever RigelMCMod issues a ban (via {@code /ban}, {@code /tban}, or
 * {@code /permban}), including every individual entry created as part of a
 * {@code /permban} cascade.
 *
 * <p>This is an informational event fired asynchronously from RigelMCMod's data layer;
 * it is not cancellable — the ban has already been persisted by the time this fires.</p>
 */
public class RigelPlayerBannedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID targetUuid;
    private final String targetIpHash;
    private final BanType type;
    private final String reason;
    private final UUID actorUuid;
    private final long expiresAtEpochMillis;
    private final String caseId;

    /**
     * Constructs a new ban event.
     *
     * @param targetUuid the banned player's UUID, or {@code null} for a pure IP-only ban entry
     * @param targetIpHash the banned IP's salted hash, or {@code null} for a pure name-only ban entry
     * @param type the kind of ban entry this is
     * @param reason the reason given for the ban
     * @param actorUuid the staff member who issued the ban, or {@code null} if issued by console
     * @param expiresAtEpochMillis epoch millis this ban expires at, or {@code -1} if permanent
     * @param caseId the shared cascade case id if this entry was created as part of a
     *     {@code /permban} name↔IP cascade, or {@code null} for a standalone ban
     */
    public RigelPlayerBannedEvent(
            @Nullable UUID targetUuid,
            @Nullable String targetIpHash,
            @NotNull BanType type,
            @NotNull String reason,
            @Nullable UUID actorUuid,
            long expiresAtEpochMillis,
            @Nullable String caseId) {
        super(true); // always fired off the main thread
        this.targetUuid = targetUuid;
        this.targetIpHash = targetIpHash;
        this.type = type;
        this.reason = reason;
        this.actorUuid = actorUuid;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        this.caseId = caseId;
    }

    /**
     * The player this ban entry targets, if it targets one at all.
     *
     * @return the banned player's UUID, or {@code null} for a pure IP-only ban entry
     */
    @Nullable
    public UUID getTargetUuid() {
        return targetUuid;
    }

    /**
     * The IP this ban entry targets, if it targets one at all.
     *
     * @return the banned IP's salted hash, or {@code null} for a pure name-only ban entry
     */
    @Nullable
    public String getTargetIpHash() {
        return targetIpHash;
    }

    /**
     * The kind of ban entry this event represents.
     *
     * @return the ban type
     */
    @NotNull
    public BanType getType() {
        return type;
    }

    /**
     * The reason given for the ban.
     *
     * @return the ban reason
     */
    @NotNull
    public String getReason() {
        return reason;
    }

    /**
     * The staff member who issued the ban.
     *
     * @return the actor's UUID, or {@code null} if issued by console
     */
    @Nullable
    public UUID getActorUuid() {
        return actorUuid;
    }

    /**
     * When this ban expires.
     *
     * @return epoch millis this ban expires at, or {@code -1} if permanent
     */
    public long getExpiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    /**
     * The shared cascade case id, for a ban created as part of a {@code /permban}
     * name/IP cascade.
     *
     * @return the case id, or {@code null} for a standalone ban
     */
    @Nullable
    public String getCaseId() {
        return caseId;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit's required static handler-list accessor.
     *
     * @return this event's static handler list
     */
    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** The kind of ban entry this event represents. */
    public enum BanType {
        /** A name-targeted ban with a fixed expiry, e.g. from {@code /ban} or {@code /tban}. */
        NAME_TEMP,
        /** A name-targeted ban with no expiry, e.g. from {@code /permban}. */
        NAME_PERM,
        /** An IP-targeted ban with a fixed expiry. */
        IP_TEMP,
        /** An IP-targeted ban with no expiry, e.g. one entry of a {@code /permban} cascade. */
        IP_PERM
    }
}
