package org.rigelmc.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when a player is frozen or unfrozen via RigelMCMod's {@code punish/freeze} module.
 * Informational only — not cancellable, since freeze is typically an immediate-response
 * punishment tool and third-party plugins vetoing it silently would be a footgun.
 */
public class RigelFreezeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID targetUuid;
    private final boolean frozen;
    private final UUID actorUuid;
    private final String reason;

    /**
     * Constructs a new freeze/unfreeze event.
     *
     * @param targetUuid the player being frozen or unfrozen
     * @param frozen {@code true} if this is a freeze, {@code false} for an unfreeze
     * @param actorUuid the staff member who issued it, or {@code null} if system-issued
     * @param reason the reason given, or {@code null} if none
     */
    public RigelFreezeEvent(
            @NotNull UUID targetUuid, boolean frozen, @Nullable UUID actorUuid, @Nullable String reason) {
        super(!org.bukkit.Bukkit.isPrimaryThread());
        this.targetUuid = targetUuid;
        this.frozen = frozen;
        this.actorUuid = actorUuid;
        this.reason = reason;
    }

    /**
     * The player being frozen or unfrozen.
     *
     * @return the target's UUID
     */
    @NotNull
    public UUID getTargetUuid() {
        return targetUuid;
    }

    /**
     * Whether this event represents a freeze or an unfreeze.
     *
     * @return {@code true} if this event represents a freeze, {@code false} for an unfreeze
     */
    public boolean isFrozen() {
        return frozen;
    }

    /**
     * The staff member who issued this freeze/unfreeze.
     *
     * @return the actor's UUID, or {@code null} if system-issued
     */
    @Nullable
    public UUID getActorUuid() {
        return actorUuid;
    }

    /**
     * The reason given for this freeze/unfreeze.
     *
     * @return the reason, or {@code null} if none was given
     */
    @Nullable
    public String getReason() {
        return reason;
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
}
