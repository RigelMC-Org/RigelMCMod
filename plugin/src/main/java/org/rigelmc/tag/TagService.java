package org.rigelmc.tag;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.chat.ColorCodes;
import org.rigelmc.chat.ImpersonationGuard;

/**
 * {@code /tag} business logic - a self-service personal <b>prefix</b> shown before a
 * player's name in chat/tab list, TFM-style (not a trailing suffix - see
 * {@code chat.PlayerDisplayService#fullDisplayFor}, which positions it there and lets it
 * override the generic {@code [OP]} default-rank prefix).
 *
 * <p>Deliberately <b>session-only, never persisted</b>: unlike rank/title (which come
 * from the database and must survive relogs) a tag resets the moment a player leaves -
 * this is an explicit design requirement, not an oversight, so there's no DAO/table
 * backing this class at all, just an in-memory cache cleared on quit (see
 * {@code core.PlayerLoginListener#onQuit}).</p>
 */
public final class TagService {

    public static final int MAX_LENGTH = 24;

    private final Map<UUID, Component> cache = new ConcurrentHashMap<>();

    /** Pure validation (color codes, length, impersonation guard) - no I/O. */
    @NotNull
    public static Optional<String> validate(@NotNull String rawTag) {
        String stripped = PlainTextComponentSerializer.plainText().serialize(ColorCodes.parse(rawTag));
        if (stripped.isBlank()) {
            return Optional.of("Tag cannot be empty.");
        }
        if (stripped.length() > MAX_LENGTH) {
            return Optional.of("Tag must be " + MAX_LENGTH + " characters or fewer (excluding color codes).");
        }
        if (ImpersonationGuard.looksLikeImpersonation(rawTag)) {
            return Optional.of("That tag isn't allowed.");
        }
        return Optional.empty();
    }

    public void set(@NotNull UUID uuid, @NotNull String rawTag) {
        cache.put(uuid, toComponent(rawTag));
    }

    public void clear(@NotNull UUID uuid) {
        cache.remove(uuid);
    }

    /** Same as {@link #clear} - called on quit, named separately for readability at the call site. */
    public void evict(@NotNull UUID uuid) {
        cache.remove(uuid);
    }

    @NotNull
    public Component tagFor(@NotNull UUID uuid) {
        return cache.getOrDefault(uuid, Component.empty());
    }

    public boolean hasTag(@NotNull UUID uuid) {
        return cache.containsKey(uuid);
    }

    @NotNull
    private static Component toComponent(String rawTag) {
        return ColorCodes.parse(rawTag);
    }
}
