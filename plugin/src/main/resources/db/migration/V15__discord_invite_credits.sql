-- RigelMCMod schema v15: Discord invite tracking -> in-game Coin credits. A PENDING row is
-- inserted immediately on join, keyed by the inviter's Discord user id (not UUID) so it
-- doesn't matter whether they've linked their account yet - a periodic sweep
-- (economy.InviteCreditService#sweep) re-attempts UUID resolution every cycle and only
-- pays out once resolution succeeds and the minimum-stay window has elapsed. reward_amount
-- is snapshotted at schedule time so a later config change never retroactively alters a
-- pending credit. See docs/architecture.md "Economy: Discord-invite-tracked currency".

CREATE TABLE rigel_pending_invite_credits (
    id                       INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    discord_guild_id         VARCHAR(32)  NOT NULL,
    invite_code              VARCHAR(16)  NOT NULL,
    inviter_discord_user_id  VARCHAR(32)  NOT NULL,
    invited_discord_user_id  VARCHAR(32)  NOT NULL,
    reward_amount            BIGINT       NOT NULL,
    joined_at                BIGINT       NOT NULL,
    eligible_at              BIGINT       NOT NULL,
    status                   VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    credited_at              BIGINT,
    credited_uuid            VARCHAR(36)
);
CREATE INDEX idx_invite_credits_status_eligible ON rigel_pending_invite_credits (status, eligible_at);
CREATE INDEX idx_invite_credits_invited_status ON rigel_pending_invite_credits (invited_discord_user_id, status);
