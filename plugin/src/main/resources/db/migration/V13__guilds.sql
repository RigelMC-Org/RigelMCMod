-- RigelMCMod schema v13: guild system (roster, roles). plot_area_id/plot_slot_index are
-- added now (nullable) even though nothing populates them until a later sub-phase, to
-- avoid a second schema-churn migration when the plot world lands. See
-- docs/architecture.md "Guild system: roster, roles, and the plot world".

CREATE TABLE rigel_guilds (
    id              INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    name            VARCHAR(24)  NOT NULL,
    name_lower      VARCHAR(24)  NOT NULL,
    owner_uuid      VARCHAR(36)  NOT NULL,
    plot_area_id    INTEGER,
    plot_slot_index INTEGER,
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL
);
CREATE UNIQUE INDEX idx_guilds_name ON rigel_guilds (name_lower);
-- NULL plot_slot_index (every guild, until plots exist) doesn't collide with itself under
-- a UNIQUE index - SQL treats each NULL as distinct for uniqueness purposes - so this only
-- actually starts enforcing "one guild per slot" once slots are assigned.
CREATE UNIQUE INDEX idx_guilds_plot_slot ON rigel_guilds (plot_slot_index);
CREATE INDEX idx_guilds_owner ON rigel_guilds (owner_uuid);

-- role: OWNER | OFFICER | MEMBER (guild.GuildRole). A player belongs to at most one guild
-- at a time - enforced by the unique index on member_uuid alone, not just the PK.
CREATE TABLE rigel_guild_members (
    guild_id    INTEGER     NOT NULL,
    member_uuid VARCHAR(36) NOT NULL,
    role        VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    joined_at   BIGINT      NOT NULL,
    PRIMARY KEY (guild_id, member_uuid)
);
CREATE UNIQUE INDEX idx_guild_members_uuid ON rigel_guild_members (member_uuid);
