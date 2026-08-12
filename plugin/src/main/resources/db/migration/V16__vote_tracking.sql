-- RigelMCMod schema v16: vote-site streak/milestone tracking - see vote.VoteRecordService.
-- One row per player who has ever voted; recorded by /vote record <player>, run by an
-- external vote-listener plugin (any of them - this deliberately doesn't soft-depend on
-- a specific one, see VoteModule's javadoc).

CREATE TABLE rigel_vote_records (
    uuid           VARCHAR(36) NOT NULL PRIMARY KEY,
    total_votes    INTEGER     NOT NULL DEFAULT 0,
    current_streak INTEGER     NOT NULL DEFAULT 0,
    last_vote_at   BIGINT,
    updated_at     BIGINT      NOT NULL
);
