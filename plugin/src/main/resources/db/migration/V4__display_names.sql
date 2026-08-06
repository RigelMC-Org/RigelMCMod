-- Personal display customization: /nick nickname. See docs/architecture.md's
-- chat/display-name design. (/tag is deliberately NOT persisted here - it's
-- session-only by design, reset on quit; see tag.TagService's javadoc.)

CREATE TABLE rigel_player_nicks (
    uuid      VARCHAR(36) NOT NULL PRIMARY KEY,
    nickname  VARCHAR(32) NOT NULL,
    set_at    BIGINT      NOT NULL
);
