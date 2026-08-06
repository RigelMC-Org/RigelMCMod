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

    public RigelFreezeEvent(
            @NotNull UUID targetUuid, boolean frozen, @Nullable UUID actorUuid, @Nullable String reason) {
        super(!org.bukkit.Bukkit.isPrimaryThread());
        this.targetUuid = targetUuid;
        this.frozen = frozen;
        this.actorUuid = actorUuid;
        this.reason = reason;
    }

    @NotNull
    public UUID getTargetUuid() {
        return targetUuid;
    }

    /** @return {@code true} if this event represents a freeze, {@code false} for an unfreeze */
    public boolean isFrozen() {
        return frozen;
    }

    @Nullable
    public UUID getActorUuid() {
        return actorUuid;
    }

    @Nullable
    public String getReason() {
        return reason;
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
}
