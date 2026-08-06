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

    /** @return the banned player's UUID, or {@code null} for a pure IP-only ban entry */
    @Nullable
    public UUID getTargetUuid() {
        return targetUuid;
    }

    /** @return the banned IP's salted hash, or {@code null} for a pure name-only ban entry */
    @Nullable
    public String getTargetIpHash() {
        return targetIpHash;
    }

    @NotNull
    public BanType getType() {
        return type;
    }

    @NotNull
    public String getReason() {
        return reason;
    }

    /** @return the staff member who issued the ban, or {@code null} if issued by console */
    @Nullable
    public UUID getActorUuid() {
        return actorUuid;
    }

    /** @return epoch millis this ban expires at, or {@code -1} if permanent */
    public long getExpiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    /**
     * @return the shared cascade case id if this entry was created as part of a
     *     {@code /permban} name↔IP cascade, or {@code null} for a standalone ban
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

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** The kind of ban entry this event represents. */
    public enum BanType {
        NAME_TEMP,
        NAME_PERM,
        IP_TEMP,
        IP_PERM
    }
}
