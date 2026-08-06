package org.rigelmc.vanish;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * In-memory vanish state - see docs/architecture.md's Essentials collision table entry
 * for "Vanish". Deliberately session-only (not persisted): a relog always comes back
 * visible rather than risk a staff member forgetting they were vanished after a
 * restart. Kept free of Bukkit types so the toggle/query logic is unit-testable; actual
 * visibility application ({@code Player#hidePlayer}/{@code showPlayer}) lives in
 * {@link VanishModule} and {@link VanishListener}, which own the Bukkit-facing side.
 */
public final class VanishService {

    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public boolean isVanished(@NotNull UUID uuid) {
        return vanished.contains(uuid);
    }

    /** @return {@code true} if the player is now vanished, {@code false} if now visible */
    public boolean toggle(@NotNull UUID uuid) {
        if (!vanished.add(uuid)) {
            vanished.remove(uuid);
            return false;
        }
        return true;
    }

    public void clear(@NotNull UUID uuid) {
        vanished.remove(uuid);
    }

    @NotNull
    public Set<UUID> vanishedPlayers() {
        return Set.copyOf(vanished);
    }
}
