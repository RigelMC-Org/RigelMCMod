package org.rigelmc.punish.warn;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Time-decayed per-player strike counter backing {@code /warn}'s auto-escalation - TFM ref:
 * {@code AutoEject}/{@code StrikeList}, studied directly, but reworked to actually connect
 * to {@code /warn} end-to-end: TFM's own {@code /warn} and its real auto-ban escalation are
 * two disconnected systems there (its {@code /warn} is purely cosmetic - a strike-of-
 * lightning flourish and a counter that never triggers a ban; the real escalation is driven
 * entirely by anti-cheat detections, never by {@code /warn}). RigelMCMod wires the two
 * together properly instead, so a staff-issued warning actually means something.
 *
 * <p>Keyed by UUID, not IP like TFM's {@code StrikeList}: RigelMCMod already has IP-aware
 * cascading bans via {@code punish.ban.BanService}/{@code data.dao.IpHistoryDao} elsewhere,
 * so strikes don't need their own IP-keying on top of that.</p>
 */
public final class StrikeService {

    private final StrikeDao strikeDao;

    public StrikeService(@NotNull StrikeDao strikeDao) {
        this.strikeDao = strikeDao;
    }

    /**
     * Records a strike, applying decay first: if the player's last strike was longer than
     * {@code decayHours} ago, their effective prior count is treated as 0 before
     * incrementing - a full reset, not a gradual step-down, matching TFM's own {@code
     * StrikeRecord#effectiveCount} semantics (simpler, and this project has no existing
     * "partial decay" concept anywhere else to be consistent with).
     *
     * @param decayHours {@code <= 0} means strikes never decay
     * @return the new strike count after this one
     */
    public int recordStrike(@NotNull UUID uuid, long decayHours, long nowEpochMillis) throws SQLException {
        int effectivePrior = effectiveCount(uuid, decayHours, nowEpochMillis);
        int newCount = effectivePrior + 1;
        strikeDao.upsert(uuid, newCount, nowEpochMillis);
        return newCount;
    }

    /** @return the player's current strike count with decay applied, without recording a new one. */
    public int effectiveCount(@NotNull UUID uuid, long decayHours, long nowEpochMillis) throws SQLException {
        return strikeDao
                .find(uuid)
                .filter(record -> decayHours <= 0
                        || nowEpochMillis - record.lastStrikeAt() <= Duration.ofHours(decayHours).toMillis())
                .map(StrikeRecord::strikeCount)
                .orElse(0);
    }
}
