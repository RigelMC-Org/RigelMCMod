package org.rigelmc.punish.mute;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.audit.AuditLogService;

/**
 * Mute business logic backing {@code /punish mute}/{@code /punish unmute} and the
 * chat-blocking listener in {@code chat.ChatModule}. Pure JDBC, no Bukkit types.
 */
public final class MuteService {

    private final MuteDao muteDao;
    private final AuditLogService auditLogService;

    public MuteService(@NotNull MuteDao muteDao, @NotNull AuditLogService auditLogService) {
        this.muteDao = muteDao;
        this.auditLogService = auditLogService;
    }

    public void mute(
            @NotNull UUID uuid, @Nullable UUID mutedBy, @NotNull String reason, long nowEpochMillis, @Nullable Long expiresAtEpochMillis)
            throws SQLException {
        muteDao.upsert(uuid, mutedBy, reason, nowEpochMillis, expiresAtEpochMillis);
        auditLogService.record(mutedBy, "MUTE", uuid, reason);
    }

    /** @return {@code true} if the player had an active mute record removed */
    public boolean unmute(@NotNull UUID uuid) throws SQLException {
        boolean removed = muteDao.delete(uuid) > 0;
        if (removed) {
            auditLogService.record(null, "UNMUTE", uuid, null);
        }
        return removed;
    }

    public boolean isMuted(@NotNull UUID uuid, long nowEpochMillis) throws SQLException {
        Optional<MuteDao.MuteRecord> record = muteDao.find(uuid);
        if (record.isEmpty()) {
            return false;
        }
        if (record.get().isExpired(nowEpochMillis)) {
            muteDao.delete(uuid); // lazily clean up expired mutes on next check
            return false;
        }
        return true;
    }

    @NotNull
    public Optional<MuteDao.MuteRecord> find(@NotNull UUID uuid) throws SQLException {
        return muteDao.find(uuid);
    }
}
