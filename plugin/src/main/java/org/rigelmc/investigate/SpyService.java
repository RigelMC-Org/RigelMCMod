package org.rigelmc.investigate;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Session-only, in-memory opt-in toggles for the three "spy" relay tools ({@code
 * /cmdspy}, {@code /signspy}, {@code /potionspy}) - deliberately simpler than TFM's own
 * persisted-to-DB toggles, matching this codebase's established pattern for staff opt-in
 * state (see {@code vanish.VanishService}, {@code tag.TagService}): a relog always starts
 * fresh rather than risk a staff member forgetting a spy mode was left on after a restart.
 */
public final class SpyService {

    private final Set<UUID> commandSpies = ConcurrentHashMap.newKeySet();
    private final Set<UUID> signSpies = ConcurrentHashMap.newKeySet();
    private final Set<UUID> potionSpies = ConcurrentHashMap.newKeySet();

    public boolean isCommandSpy(@NotNull UUID uuid) {
        return commandSpies.contains(uuid);
    }

    public boolean isSignSpy(@NotNull UUID uuid) {
        return signSpies.contains(uuid);
    }

    public boolean isPotionSpy(@NotNull UUID uuid) {
        return potionSpies.contains(uuid);
    }

    public void setCommandSpy(@NotNull UUID uuid, boolean on) {
        setFlag(commandSpies, uuid, on);
    }

    /** @return {@code true} if now on, {@code false} if now off */
    public boolean toggleSignSpy(@NotNull UUID uuid) {
        return toggle(signSpies, uuid);
    }

    /** @return {@code true} if now on, {@code false} if now off */
    public boolean togglePotionSpy(@NotNull UUID uuid) {
        return toggle(potionSpies, uuid);
    }

    @NotNull
    public Set<UUID> commandSpies() {
        return Set.copyOf(commandSpies);
    }

    @NotNull
    public Set<UUID> signSpies() {
        return Set.copyOf(signSpies);
    }

    @NotNull
    public Set<UUID> potionSpies() {
        return Set.copyOf(potionSpies);
    }

    /** Call on quit - a relog always starts every spy mode fresh, see class javadoc. */
    public void clear(@NotNull UUID uuid) {
        commandSpies.remove(uuid);
        signSpies.remove(uuid);
        potionSpies.remove(uuid);
    }

    private static void setFlag(Set<UUID> set, UUID uuid, boolean on) {
        if (on) {
            set.add(uuid);
        } else {
            set.remove(uuid);
        }
    }

    private static boolean toggle(Set<UUID> set, UUID uuid) {
        if (!set.add(uuid)) {
            set.remove(uuid);
            return false;
        }
        return true;
    }
}
