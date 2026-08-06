-- RigelMCMod schema v8: /protectarea (region protection).
--
-- Deliberately DB-backed rather than a flat file (TFM's own protectedareas.yml, rewritten
-- wholesale on every mutation - see protect.area.ProtectAreaService's javadoc), which also
-- makes this correctly usable on a MySQL-shared multi-server network.

-- One row per protected region. shape_type is a forward-compat discriminator only -
-- 'CUBOID' is the only value written or read today; see protect.area's package
-- documentation for why polygon support is deliberately out of scope, not just deferred.
CREATE TABLE rigel_protect_areas (
    id           INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    name         VARCHAR(64)  NOT NULL,
    name_lower   VARCHAR(64)  NOT NULL,
    world        VARCHAR(64)  NOT NULL,
    shape_type   VARCHAR(16)  NOT NULL DEFAULT 'CUBOID',
    min_x        INTEGER      NOT NULL,
    min_y        INTEGER      NOT NULL,
    min_z        INTEGER      NOT NULL,
    max_x        INTEGER      NOT NULL,
    max_y        INTEGER      NOT NULL,
    max_z        INTEGER      NOT NULL,
    priority     INTEGER      NOT NULL DEFAULT 0,
    enabled      INTEGER      NOT NULL DEFAULT 1,
    owner_uuid   VARCHAR(36),
    created_by   VARCHAR(36),
    created_at   BIGINT       NOT NULL,
    updated_by   VARCHAR(36),
    updated_at   BIGINT       NOT NULL
);
CREATE UNIQUE INDEX idx_protect_areas_name ON rigel_protect_areas (name_lower);
CREATE INDEX idx_protect_areas_world ON rigel_protect_areas (world);

-- Per-region trusted-bypass list - the actual "strictly more useful than TFM's binary
-- any-admin-bypasses model" mechanism: any player, staff or not, can be added here to
-- bypass this ONE region's flags, without needing any RigelMCMod rank at all.
CREATE TABLE rigel_protect_area_members (
    area_id     INTEGER     NOT NULL,
    member_uuid VARCHAR(36) NOT NULL,
    added_by    VARCHAR(36),
    added_at    BIGINT      NOT NULL,
    PRIMARY KEY (area_id, member_uuid)
);

-- Per-region flag overrides. Absence of a row for a given flag_key means that flag's coded
-- default (see protect.area.AreaFlag) applies - deliberately no config-driven default-flags
-- layer, to keep this a small, fixed vocabulary rather than an open-ended flag registry.
CREATE TABLE rigel_protect_area_flags (
    area_id    INTEGER     NOT NULL,
    flag_key   VARCHAR(32) NOT NULL,
    flag_value VARCHAR(8)  NOT NULL,
    PRIMARY KEY (area_id, flag_key)
);
