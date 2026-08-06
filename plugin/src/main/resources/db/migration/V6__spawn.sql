-- The server's spawn location, set via /setspawn. Single logical row (id = 1) - see
-- world.SpawnService. The join/respawn behavior toggles that use this location live in
-- config.yml instead (world.spawn.send-on-join/send-on-respawn) - only the coordinates
-- themselves, a runtime-mutated admin action, belong in the database.
CREATE TABLE rigel_spawn (
    id     INTEGER     NOT NULL PRIMARY KEY,
    world  VARCHAR(64) NOT NULL,
    x      DOUBLE      NOT NULL,
    y      DOUBLE      NOT NULL,
    z      DOUBLE      NOT NULL,
    yaw    REAL        NOT NULL,
    pitch  REAL        NOT NULL,
    set_by VARCHAR(36),
    set_at BIGINT      NOT NULL
);
