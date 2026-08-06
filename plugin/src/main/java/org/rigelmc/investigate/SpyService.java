package org.rigelmc.investigate;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Session-only, in-memory opt-in toggles for the four "spy" relay tools ({@code
 * /cmdspy}, {@code /signspy}, {@code /potionspy}, {@code /bookspy}) - deliberately simpler
 * than TFM's own persisted-to-DB toggles, matching this codebase's established pattern for
 * staff opt-in state (see {@code vanish.VanishService}, {@code tag.TagService}): a relog
 * always starts fresh rather than risk a staff member forgetting a spy mode was left on
 * after a restart. {@code /bookspy} has no TFM equivalent to study - designed here to match
 * the other three's shape exactly.
 */
public final class SpyService {

    private final Set<UUID> commandSpies = ConcurrentHashMap.newKeySet();
    private final Set<UUID> signSpies = ConcurrentHashMap.newKeySet();
    private final Set<UUID> potionSpies = ConcurrentHashMap.newKeySet();
    private final Set<UUID> bookSpies = ConcurrentHashMap.newKeySet();

    public boolean isCommandSpy(@NotNull UUID uuid) {
        return commandSpies.contains(uuid);
    }

    public boolean isSignSpy(@NotNull UUID uuid) {
        return signSpies.contains(uuid);
    }

    public boolean isPotionSpy(@NotNull UUID uuid) {
        return potionSpies.contains(uuid);
    }

    public boolean isBookSpy(@NotNull UUID uuid) {
        return bookSpies.contains(uuid);
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

    /** @return {@code true} if now on, {@code false} if now off */
    public boolean toggleBookSpy(@NotNull UUID uuid) {
        return toggle(bookSpies, uuid);
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

    @NotNull
    public Set<UUID> bookSpies() {
        return Set.copyOf(bookSpies);
    }

    /** Call on quit - a relog always starts every spy mode fresh, see class javadoc. */
    public void clear(@NotNull UUID uuid) {
        commandSpies.remove(uuid);
        signSpies.remove(uuid);
        potionSpies.remove(uuid);
        bookSpies.remove(uuid);
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
