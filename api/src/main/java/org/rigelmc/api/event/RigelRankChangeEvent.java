package org.rigelmc.api.event;

import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when a player's RigelMCMod rank is about to change (promotion, demotion, or
 * manual set), including the one-time first-admin bootstrap promotion. Cancellable —
 * other plugins (e.g. a LuckPerms sync addon) may veto the change.
 */
public class RigelRankChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID targetUuid;
    private final String previousRankId;
    private final String newRankId;
    private final UUID actorUuid;
    private boolean cancelled;

    public RigelRankChangeEvent(
            @NotNull UUID targetUuid,
            @Nullable String previousRankId,
            @NotNull String newRankId,
            @Nullable UUID actorUuid) {
        super(!org.bukkit.Bukkit.isPrimaryThread());
        this.targetUuid = targetUuid;
        this.previousRankId = previousRankId;
        this.newRankId = newRankId;
        this.actorUuid = actorUuid;
    }

    @NotNull
    public UUID getTargetUuid() {
        return targetUuid;
    }

    /** @return the rank id held before this change, or {@code null} if previously unranked */
    @Nullable
    public String getPreviousRankId() {
        return previousRankId;
    }

    @NotNull
    public String getNewRankId() {
        return newRankId;
    }

    /** @return the staff member who issued the change, or {@code null} if system-issued */
    @Nullable
    public UUID getActorUuid() {
        return actorUuid;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
