package org.rigelmc.rank;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * Title business logic: seeding the default title set and resolving which titles a
 * player holds. Grants no permissions - see docs/architecture.md "Ranks vs. Titles".
 * Pure JDBC, no Bukkit types, unit-testable without a live server.
 */
public final class TitleService {

    private final TitleRepository titleRepository;
    private volatile Map<String, Title> titlesById = Map.of();

    public TitleService(@NotNull TitleRepository titleRepository) {
        this.titleRepository = titleRepository;
    }

    /** Seeds the default titles if {@code rigel_titles} is empty, then loads them into memory. */
    public synchronized void initialize() throws SQLException {
        if (titleRepository.isEmpty()) {
            for (Title title : Title.defaultTitles()) {
                titleRepository.insert(title);
            }
        }
        Map<String, Title> loaded = new LinkedHashMap<>();
        for (Title title : titleRepository.findAll()) {
            loaded.put(title.id(), title);
        }
        this.titlesById = Map.copyOf(loaded);
    }

    @NotNull
    public Optional<Title> title(@NotNull String titleId) {
        return Optional.ofNullable(titlesById.get(titleId));
    }

    public void grant(@NotNull UUID uuid, @NotNull String titleId, UUID grantedBy, long nowEpochMillis)
            throws SQLException {
        if (!titlesById.containsKey(titleId)) {
            throw new IllegalArgumentException("Unknown title: " + titleId);
        }
        titleRepository.grant(uuid, titleId, grantedBy, nowEpochMillis);
    }

    /**
     * Grants the title only if the player doesn't already hold it - safe to call
     * repeatedly (e.g. on every join for a config-driven auto-grant list) without
     * hitting {@code rigel_player_titles}' primary-key constraint on a duplicate grant.
     */
    public void ensureGranted(@NotNull UUID uuid, @NotNull String titleId, UUID grantedBy, long nowEpochMillis)
            throws SQLException {
        if (!titleIdsFor(uuid).contains(titleId)) {
            grant(uuid, titleId, grantedBy, nowEpochMillis);
        }
    }

    public void revoke(@NotNull UUID uuid, @NotNull String titleId) throws SQLException {
        titleRepository.revoke(uuid, titleId);
    }

    /** @return every title id the player holds, in the order they were granted. */
    @NotNull
    public Set<String> titleIdsFor(@NotNull UUID uuid) throws SQLException {
        return titleRepository.titleIdsFor(uuid);
    }

    /** @return this player's title prefixes, concatenated in grant order. */
    @NotNull
    public String composedPrefix(@NotNull UUID uuid) throws SQLException {
        List<String> prefixes = titleRepository.titleIdsFor(uuid).stream()
                .map(titlesById::get)
                .filter(java.util.Objects::nonNull)
                .map(Title::prefix)
                .collect(Collectors.toList());
        return String.join("", prefixes);
    }
}
