-- RigelMCMod schema v1: rank ladder + titles, IP history, bans, mutes, audit log.
-- Written to be portable across SQLite and MySQL/MariaDB (the two supported backends) -
-- no dialect-specific upsert syntax, no AUTOINCREMENT/AUTO_INCREMENT keyword mismatches
-- exposed to callers (see MigrationRunner for how these are executed statement-by-statement).

CREATE TABLE rigel_players (
    uuid            VARCHAR(36)  NOT NULL PRIMARY KEY,
    last_known_name VARCHAR(64)  NOT NULL,
    identity_type   VARCHAR(16)  NOT NULL DEFAULT 'JAVA',
    rank_id         VARCHAR(32)  NOT NULL DEFAULT 'default',
    first_seen_at   BIGINT       NOT NULL,
    last_seen_at    BIGINT       NOT NULL
);

CREATE TABLE rigel_ranks (
    rank_id      VARCHAR(32)  NOT NULL PRIMARY KEY,
    display_name VARCHAR(64)  NOT NULL,
    prefix       VARCHAR(64)  NOT NULL DEFAULT '',
    weight       INTEGER      NOT NULL,
    is_default   INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE rigel_rank_permissions (
    rank_id         VARCHAR(32)  NOT NULL,
    permission_node VARCHAR(128) NOT NULL,
    value           INTEGER      NOT NULL DEFAULT 1,
    PRIMARY KEY (rank_id, permission_node)
);

CREATE TABLE rigel_titles (
    title_id     VARCHAR(32) NOT NULL PRIMARY KEY,
    display_name VARCHAR(64) NOT NULL,
    prefix       VARCHAR(64) NOT NULL DEFAULT ''
);

CREATE TABLE rigel_player_titles (
    uuid        VARCHAR(36) NOT NULL,
    title_id    VARCHAR(32) NOT NULL,
    granted_at  BIGINT      NOT NULL,
    granted_by  VARCHAR(36),
    PRIMARY KEY (uuid, title_id)
);

-- Full login history (not just last-known IP) - powers /permban's cascading name<->IP
-- resolution in both directions. ip_hash is a salted HMAC, never a raw address; see
-- org.rigelmc.identity.IpHasher.
CREATE TABLE rigel_ip_history (
    uuid          VARCHAR(36)  NOT NULL,
    ip_hash       VARCHAR(64)  NOT NULL,
    first_seen_at BIGINT       NOT NULL,
    last_seen_at  BIGINT       NOT NULL,
    PRIMARY KEY (uuid, ip_hash)
);

CREATE INDEX idx_ip_history_iphash ON rigel_ip_history (ip_hash);

-- One row per ban *entry* - a /permban cascade inserts multiple rows (one per name, one
-- per IP) sharing the same case_id so they can be viewed/lifted together.
CREATE TABLE rigel_bans (
    id               INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    case_id          VARCHAR(36),
    type             VARCHAR(16)  NOT NULL,
    target_uuid      VARCHAR(36),
    target_last_name VARCHAR(64),
    target_ip_hash   VARCHAR(64),
    reason           VARCHAR(256) NOT NULL,
    banned_by_uuid   VARCHAR(36),
    created_at       BIGINT       NOT NULL,
    expires_at       BIGINT,
    active           INTEGER      NOT NULL DEFAULT 1,
    revoked_by_uuid  VARCHAR(36),
    revoked_at       BIGINT
);

CREATE INDEX idx_bans_target_uuid ON rigel_bans (target_uuid);
CREATE INDEX idx_bans_target_ip ON rigel_bans (target_ip_hash);
CREATE INDEX idx_bans_case ON rigel_bans (case_id);

CREATE TABLE rigel_mutes (
    uuid          VARCHAR(36)  NOT NULL PRIMARY KEY,
    muted_by_uuid VARCHAR(36),
    reason        VARCHAR(256),
    created_at    BIGINT       NOT NULL,
    expires_at    BIGINT
);

-- Append-only accountability trail every punishment action writes to.
CREATE TABLE rigel_audit_log (
    id          INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    actor_uuid  VARCHAR(36),
    action_type VARCHAR(32)  NOT NULL,
    target_uuid VARCHAR(36),
    detail      VARCHAR(512),
    created_at  BIGINT       NOT NULL
);
