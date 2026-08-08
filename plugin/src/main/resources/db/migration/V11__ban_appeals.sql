-- RigelMCMod schema v11: ban-appeal system (public web form -> Discord approve/deny).
--
-- ban_reference is whatever the appellant's link pointed at (a permban cascade's case_id,
-- or a /ban|/tban row's own numeric id, stringified) - kept alongside the resolved ban_id
-- so the original reference is still visible even if the underlying ban row is ever
-- deleted. discord_message_id lets the posted Discord message be edited in place once a
-- decision is made (buttons removed, outcome appended) rather than posting a second
-- message. submitter_ip_hash reuses the same salted-hash approach as rigel_ip_history -
-- see identity.IpHasher - for per-IP rate limiting on this public, unauthenticated form.
CREATE TABLE rigel_ban_appeals (
    id                  INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    ban_reference       VARCHAR(64)  NOT NULL,
    ban_id              BIGINT       NOT NULL,
    message             VARCHAR(1024) NOT NULL,
    contact             VARCHAR(256),
    submitted_at        BIGINT       NOT NULL,
    submitter_ip_hash   VARCHAR(64),
    status              VARCHAR(16)  NOT NULL DEFAULT 'pending',
    discord_message_id  VARCHAR(32),
    decided_by_uuid     VARCHAR(36),
    decided_at          BIGINT
);
CREATE INDEX idx_ban_appeals_reference ON rigel_ban_appeals (ban_reference);
CREATE INDEX idx_ban_appeals_status ON rigel_ban_appeals (status);
