package org.rigelmc.guild.plot;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * In-memory "I am now editing the shared plot world" toggle for staff - user-requested:
 * nobody should be able to build/break outside their own plot, not even the wall itself, not
 * even staff, unless they've explicitly turned this on. See {@link GuildPlotBoundaryGuard}
 * for the actual enforcement (this class is pure state, same "session-only, no persistence"
 * shape {@code vanish.VanishService} already established - a relog always comes back with
 * bypass off, rather than risk a Senior Admin forgetting they left it on after a restart).
 *
 * <p>Deliberately does <b>not</b> check rank itself - {@link GuildPlotBoundaryGuard} is
 * responsible for requiring Senior Admin <i>and</i> this flag together; this class only
 * ever tracks the flag.</p>
 */
public final class GuildPlotBypassService {

    private final Set<UUID> bypassing = ConcurrentHashMap.newKeySet();

    public boolean isBypassing(@NotNull UUID uuid) {
        return bypassing.contains(uuid);
    }

    /** @return {@code true} if bypass is now on for this player, {@code false} if now off. */
    public boolean toggle(@NotNull UUID uuid) {
        if (!bypassing.add(uuid)) {
            bypassing.remove(uuid);
            return false;
        }
        return true;
    }

    public void clear(@NotNull UUID uuid) {
        bypassing.remove(uuid);
    }
}
