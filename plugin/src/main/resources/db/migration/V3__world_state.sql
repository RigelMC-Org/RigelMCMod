-- Tracks per-world state that needs to survive restarts - currently just the flatlands
-- autowipe cadence, so a server restart doesn't reset a long wipe interval's countdown
-- back to zero every time. See docs/architecture.md "world/" module.
CREATE TABLE rigel_world_state (
    world_name   VARCHAR(64) NOT NULL PRIMARY KEY,
    last_wipe_at BIGINT      NOT NULL
);
