-- Wallet & P2P Transfer schema.
-- The invariants are enforced by the database, not by app-side coordination.

-- Wallets. Balance is integer paise, never floats.
CREATE TABLE wallets (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user   TEXT        NOT NULL,
    balance_paise BIGINT     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- No overdraft: a balance can never be negative. A conditional debit
    -- that would violate this fails the statement rather than partially applying.
    CONSTRAINT wallets_balance_non_negative CHECK (balance_paise >= 0),

    -- Race-free get-or-create: at most one wallet per user. Two concurrent
    -- POST /wallets for the same user -> one INSERT wins, the other conflicts.
    CONSTRAINT wallets_owner_unique UNIQUE (owner_user)
);

-- Transfers. Terminal record of a money movement.
CREATE TABLE transfers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key  TEXT        NOT NULL,
    requested_by     TEXT        NOT NULL,
    from_wallet      UUID        NOT NULL REFERENCES wallets(id),
    to_wallet        UUID        NOT NULL REFERENCES wallets(id),
    amount_paise     BIGINT      NOT NULL,
    status           TEXT        NOT NULL,          -- SUCCEEDED | DECLINED
    decline_reason   TEXT,                          -- e.g. INSUFFICIENT_FUNDS
    -- request fingerprint: same key + different body => 409 conflict
    request_hash     TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT transfers_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT transfers_distinct_wallets CHECK (from_wallet <> to_wallet),

    -- Exactly-once: the idempotency key is unique. The uniqueness is committed
    -- in the SAME transaction as the debit/credit, so a retry storm produces
    -- exactly one row (one debit/credit) and every other attempt collides here.
    CONSTRAINT transfers_idempotency_unique UNIQUE (idempotency_key)
);

CREATE INDEX transfers_from_wallet_idx ON transfers (from_wallet);
CREATE INDEX transfers_to_wallet_idx   ON transfers (to_wallet);
