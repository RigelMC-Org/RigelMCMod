package org.rigelmc.audit;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thin wrapper over {@link AuditLogDao} - every punishment action (ban/tban/permban/
 * unban/mute/unmute, and future freeze/cage/warn) writes here, addressing TFM's thin
 * audit trail per docs/architecture.md. Kept as its own small service (rather than
 * inlining DAO calls) so future consumers (a {@code /history <player>} command, or the
 * web panel's audit view) have one place to query from.
 */
public final class AuditLogService {

    private final AuditLogDao auditLogDao;

    public AuditLogService(@NotNull AuditLogDao auditLogDao) {
        this.auditLogDao = auditLogDao;
    }

    public void record(
            @Nullable UUID actorUuid, @NotNull String actionType, @Nullable UUID targetUuid, @Nullable String detail)
            throws SQLException {
        auditLogDao.insert(actorUuid, actionType, targetUuid, detail, System.currentTimeMillis());
    }

    @NotNull
    public List<AuditLogDao.AuditEntry> recentFor(@NotNull UUID targetUuid, int limit) throws SQLException {
        return auditLogDao.findRecentForTarget(targetUuid, limit);
    }
}
