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

    /**
     * Constructs a new rank-change event.
     *
     * @param targetUuid the player whose rank is changing
     * @param previousRankId the rank id held before this change, or {@code null} if previously unranked
     * @param newRankId the rank id this player is changing to
     * @param actorUuid the staff member who issued the change, or {@code null} if system-issued
     */
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

    /**
     * The player whose rank is changing.
     *
     * @return the target's UUID
     */
    @NotNull
    public UUID getTargetUuid() {
        return targetUuid;
    }

    /**
     * The rank held before this change.
     *
     * @return the previous rank id, or {@code null} if previously unranked
     */
    @Nullable
    public String getPreviousRankId() {
        return previousRankId;
    }

    /**
     * The rank this player is changing to.
     *
     * @return the new rank id
     */
    @NotNull
    public String getNewRankId() {
        return newRankId;
    }

    /**
     * The staff member who issued the change.
     *
     * @return the actor's UUID, or {@code null} if system-issued
     */
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
