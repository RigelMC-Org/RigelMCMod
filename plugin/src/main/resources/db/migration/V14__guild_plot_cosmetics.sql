-- RigelMCMod schema v14: guild plot cosmetics - a purchase receipt per (guild, cosmetic),
-- not a re-chargeable toggle. See guild.plot.PlotCosmeticService and docs/architecture.md
-- "Guild system: roster, roles, and the plot world".

CREATE TABLE rigel_guild_plot_cosmetics (
    guild_id      INTEGER      NOT NULL,
    cosmetic_key  VARCHAR(32)  NOT NULL,
    purchased_by  VARCHAR(36)  NOT NULL,
    purchased_at  BIGINT       NOT NULL,
    PRIMARY KEY (guild_id, cosmetic_key)
);
