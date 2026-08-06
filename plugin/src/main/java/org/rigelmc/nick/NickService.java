package org.rigelmc.nick;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.chat.ColorCodes;
import org.rigelmc.chat.ImpersonationGuard;

/**
 * {@code /nick} business logic, TFM-style (TFM's {@code Command_denick}/
 * {@code nickfilter}/{@code nickclean}). Persists to {@code rigel_player_nicks} and
 * caches the rendered {@link Component} per online player, same pattern as
 * {@code tag.TagService}. Note the online-player-name collision check (a nick can't
 * exactly match another currently-online player's real name) is a live-server check and
 * deliberately lives in {@code NickModule}'s command handler, not here - this class
 * stays pure/testable, that check inherently isn't.
 */
public final class NickService {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 16;

    private static final Pattern VALID_CHARS = Pattern.compile("^[A-Za-z0-9_]+$");

    private final NickDao nickDao;
    private final Map<UUID, Component> cache = new ConcurrentHashMap<>();

    public NickService(@NotNull NickDao nickDao) {
        this.nickDao = nickDao;
    }

    /** Pure validation (length, characters, impersonation guard) - no I/O, no online-player checks. */
    @NotNull
    public static Optional<String> validate(@NotNull String rawNick) {
        String stripped = PlainTextComponentSerializer.plainText().serialize(ColorCodes.parse(rawNick));
        if (stripped.length() < MIN_LENGTH || stripped.length() > MAX_LENGTH) {
            return Optional.of("Nickname must be " + MIN_LENGTH + "-" + MAX_LENGTH + " characters (excluding color codes).");
        }
        if (!VALID_CHARS.matcher(stripped).matches()) {
            return Optional.of("Nickname may only contain letters, numbers, and underscores.");
        }
        if (ImpersonationGuard.looksLikeImpersonation(rawNick)) {
            return Optional.of("That nickname isn't allowed.");
        }
        return Optional.empty();
    }

    public void set(@NotNull UUID uuid, @NotNull String rawNick, long nowEpochMillis) throws SQLException {
        nickDao.upsert(uuid, rawNick, nowEpochMillis);
        cache.put(uuid, toComponent(rawNick));
    }

    public void clear(@NotNull UUID uuid) throws SQLException {
        nickDao.delete(uuid);
        cache.remove(uuid);
    }

    /** Loads the persisted nickname (if any) into the in-memory cache. Call on join. */
    public void loadIntoCache(@NotNull UUID uuid) throws SQLException {
        Optional<String> nick = nickDao.find(uuid);
        if (nick.isPresent()) {
            cache.put(uuid, toComponent(nick.get()));
        } else {
            cache.remove(uuid);
        }
    }

    public void evict(@NotNull UUID uuid) {
        cache.remove(uuid);
    }

    @NotNull
    public Optional<Component> nicknameFor(@NotNull UUID uuid) {
        return Optional.ofNullable(cache.get(uuid));
    }

    @NotNull
    private static Component toComponent(String rawNick) {
        return ColorCodes.parse(rawNick);
    }
}
