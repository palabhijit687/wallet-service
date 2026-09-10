-- Reversal / refund support.
-- A reversal is itself a transfer row (so it reuses the ledger primitive and the
-- same conservation/no-overdraft guarantees) that points back at the original.

ALTER TABLE transfers
    ADD COLUMN reversal_of UUID NULL REFERENCES transfers(id);

-- At most ONE reversal per original transfer. A partial unique index enforces
-- "reverse-twice is impossible" at the database level, so a concurrent
-- double-reverse cannot double-refund even if two requests use different keys.
CREATE UNIQUE INDEX transfers_one_reversal_per_original
    ON transfers (reversal_of)
    WHERE reversal_of IS NOT NULL;

CREATE INDEX transfers_reversal_of_idx ON transfers (reversal_of);
