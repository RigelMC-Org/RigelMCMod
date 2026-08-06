-- RigelMCMod schema v5: /cage persistence + /warn strike tracking.

-- Active player cages (/cage). Only "is this player currently caged, and where" is
-- persisted - the block snapshot itself is rebuilt in-memory whenever a cage is (re)
-- applied, matching TFM's own session-only cageHistory - so a cage survives a restart
-- (re-applied fresh at the player's post-restart location on their next join) without
-- needing to serialize a whole block history to disk.
CREATE TABLE rigel_cages (
    uuid           VARCHAR(36)  NOT NULL PRIMARY KEY,
    world          VARCHAR(64)  NOT NULL,
    center_x       DOUBLE       NOT NULL,
    center_y       DOUBLE       NOT NULL,
    center_z       DOUBLE       NOT NULL,
    outer_material VARCHAR(64)  NOT NULL,
    inner_material VARCHAR(64)  NOT NULL,
    caged_by       VARCHAR(36),
    applied_at     BIGINT       NOT NULL
);

-- Time-decayed strike counter per identity, backing /warn's auto-escalation into the
-- existing BanService (see punish.WarnService) - TFM ref: AutoEject.java, StrikeList.java.
CREATE TABLE rigel_strikes (
    uuid           VARCHAR(36) NOT NULL PRIMARY KEY,
    strike_count   INTEGER     NOT NULL DEFAULT 0,
    last_strike_at BIGINT      NOT NULL
);
