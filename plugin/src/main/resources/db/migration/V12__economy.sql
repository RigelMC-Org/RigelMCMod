-- RigelMCMod schema v13: RigelMCMod's own currency ledger - no Vault Economy dependency
-- (VaultChatBridge covers Vault's Chat API only, confirmed by reading it directly before
-- this migration was written - it never touches net.milkbowl.vault.economy.Economy). See
-- docs/architecture.md "Economy: Discord-invite-tracked currency".

-- One row per player who has ever held a non-zero balance. Absence of a row means balance
-- 0 - not worth pre-creating a row for every player on join.
CREATE TABLE rigel_economy_accounts (
    uuid       VARCHAR(36) NOT NULL PRIMARY KEY,
    balance    BIGINT      NOT NULL DEFAULT 0,
    updated_at BIGINT      NOT NULL
);

-- One row per balance change - a full audit trail, not just the current balance (same
-- rationale as rigel_audit_log). reason is a short fixed code (see economy.LedgerReason),
-- not free text, so a future /economy history could filter by it.
CREATE TABLE rigel_economy_ledger (
    id            INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    uuid          VARCHAR(36)  NOT NULL,
    delta         BIGINT       NOT NULL,
    balance_after BIGINT       NOT NULL,
    reason        VARCHAR(32)  NOT NULL,
    reference     VARCHAR(128),
    actor_uuid    VARCHAR(36),
    created_at    BIGINT       NOT NULL
);
CREATE INDEX idx_economy_ledger_uuid ON rigel_economy_ledger (uuid);
