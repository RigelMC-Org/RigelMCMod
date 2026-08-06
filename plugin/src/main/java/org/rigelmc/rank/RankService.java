package org.rigelmc.rank;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.data.dao.PlayerDao;
import org.rigelmc.data.dao.PlayerRecord;
import org.rigelmc.identity.PlayerIdentity;

/**
 * Rank ladder business logic: loading/seeding the ladder, resolving a player's rank, and
 * weight-based comparisons. Deliberately pure JDBC + plain Java (no Bukkit types), so it's
 * unit-testable without a live server or MockBukkit - see {@code RankServiceTest}.
 *
 * <p>Bukkit-facing concerns (live {@code PermissionAttachment} updates, firing
 * {@code RigelRankChangeEvent}) live in {@link org.rigelmc.rank.PermissionGate} instead,
 * which wraps this service.</p>
 */
public final class RankService {

    private final RankRepository rankRepository;
    private final PlayerDao playerDao;
    private volatile Map<String, Rank> ranksById = Map.of();

    public RankService(@NotNull RankRepository rankRepository, @NotNull PlayerDao playerDao) {
        this.rankRepository = rankRepository;
        this.playerDao = playerDao;
    }

    /**
     * Seeds the default ladder if {@code rigel_ranks} is empty, then loads it into
     * memory. Blocking - call once during plugin startup, before accepting connections.
     */
    public synchronized void initialize() throws SQLException {
        if (rankRepository.isEmpty()) {
            for (Rank rank : Rank.defaultLadder()) {
                rankRepository.insert(rank);
            }
        }
        Map<String, Rank> loaded = new LinkedHashMap<>();
        for (Rank rank : rankRepository.findAll()) {
            loaded.put(rank.id(), rank);
        }
        this.ranksById = Map.copyOf(loaded);
    }

    @NotNull
    public Optional<Rank> rank(@NotNull String rankId) {
        return Optional.ofNullable(ranksById.get(rankId));
    }

    @NotNull
    public Collection<Rank> allRanks() {
        return ranksById.values();
    }

    @NotNull
    public Rank defaultRank() {
        return ranksById.values().stream()
                .filter(Rank::isDefault)
                .findFirst()
                .orElse(Rank.DEFAULT);
    }

    /**
     * @return the player's current rank, falling back to the default rank if
     *     unranked/unknown, <b>or if their most recently resolved identity is
     *     {@link PlayerIdentity#OFFLINE}</b>. An OFFLINE (cracked/Eaglercraft) UUID is
     *     derived purely from the connecting username ({@code UUID.nameUUIDFromBytes}),
     *     not any cryptographic identity - anyone can claim it by picking the same name.
     *     Never honor an elevated rank for a connection that can't be verified this way,
     *     even if the stored row says so - that row could be a real admin's data that
     *     just happens to share a UUID with whoever is connecting right now. JAVA
     *     (Mojang-authenticated) and BEDROCK (Floodgate-verified) identities are both
     *     still trusted normally. See {@code identity.online-mode} in config.yml and
     *     {@code identity.IdentityService} for how identity gets resolved in the first
     *     place.
     */
    @NotNull
    public Rank rankOf(@NotNull UUID uuid) throws SQLException {
        return playerDao
                .findByUuid(uuid)
                .map(record -> {
                    Rank stored = ranksById.getOrDefault(record.rankId(), defaultRank());
                    if (!stored.isDefault() && record.identityType() == PlayerIdentity.OFFLINE) {
                        return defaultRank();
                    }
                    return stored;
                })
                .orElse(defaultRank());
    }

    public boolean hasAtLeast(@NotNull UUID uuid, @NotNull String requiredRankId) throws SQLException {
        Rank required = rank(requiredRankId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rank: " + requiredRankId));
        return rankOf(uuid).weight() >= required.weight();
    }

    /**
     * @return the rank id the player held before this change, for audit/event purposes
     * @throws IllegalArgumentException if {@code newRankId} isn't a known rank
     */
    @NotNull
    public String setRank(@NotNull UUID uuid, @NotNull String newRankId) throws SQLException {
        if (!ranksById.containsKey(newRankId)) {
            throw new IllegalArgumentException("Unknown rank: " + newRankId);
        }
        String previous = playerDao
                .findByUuid(uuid)
                .map(PlayerRecord::rankId)
                .orElse(defaultRank().id());
        playerDao.setRank(uuid, newRankId);
        return previous;
    }
}
