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
     *     unranked/unknown. Trusts the stored rank unconditionally regardless of the
     *     player's resolved {@code identity.PlayerIdentity} - matching TFM's own real
     *     behavior, which has no identity-based rank distrust at all.
     *
     *     <p>This deliberately reverses an earlier version of this method, which
     *     downgraded any stored elevated rank to default for an {@code
     *     identity.PlayerIdentity#OFFLINE} connection (an offline/cracked/Eaglercraft
     *     UUID is derived purely from the connecting username, not any cryptographic
     *     identity, so it's technically spoofable). Reverted at the user's explicit,
     *     informed request: this server's real population includes GeyserMC/Floodgate
     *     <i>and</i> Eaglercraft players as core, intended parts of its community, not an
     *     edge case - the old rule made it impossible for staff who play through those
     *     paths to ever have their assigned rank actually respected, which is a much
     *     bigger, constantly-recurring practical problem than the theoretical spoofing
     *     risk it guarded against on a trust-based Free-OP server. This is the only place
     *     {@code identity.PlayerIdentity} ever gated behavior in this codebase - a full
     *     grep confirms nothing else (ban enforcement included) actually branches on it in
     *     code today, despite some surrounding javadoc/config prose discussing identity
     *     trust more broadly.</p>
     */
    @NotNull
    public Rank rankOf(@NotNull UUID uuid) throws SQLException {
        return playerDao
                .findByUuid(uuid)
                .map(record -> ranksById.getOrDefault(record.rankId(), defaultRank()))
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
