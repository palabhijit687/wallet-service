# Wallet & P2P Transfer — One-Page Write-up

**Live URL:** https://wallet-service-dwrw.onrender.com · **Repo:** github.com/palabhijit687/wallet-service
**Stack:** Spring Boot 3.3 · Java 17 · PostgreSQL 16 · Flyway · Docker · Prometheus/Micrometer. Money is integer **paise** (`BIGINT`), never floats.

## Data model
Two tables. Every invariant is a database constraint, so the app cannot violate one even with a logic bug.

- **`wallets`** — `id` (UUID PK), `owner_user` (**UNIQUE** → race-free get-or-create), `balance_paise` (`BIGINT`, **CHECK ≥ 0** → no-overdraft backstop), `created_at`/`updated_at`.
- **`transfers`** — `id`, `idempotency_key` (**UNIQUE** → exactly-once), `from_wallet`/`to_wallet` (FK, `CHECK from ≠ to`), `amount_paise` (`CHECK > 0`), `status` (`PENDING → SUCCEEDED|DECLINED`, or `REVERSED`), `decline_reason`, `request_hash` (SHA-256 of `from|to|amount`), `reversal_of` (FK; **partial UNIQUE index** → each transfer reversed at most once), `created_at`.

## Simplest-correct mechanism (conservation + no-overdraft) — and rejected alternatives
An **atomic conditional debit**:

```sql
UPDATE wallets SET balance_paise = balance_paise - :amt
 WHERE id = :from AND balance_paise >= :amt;
```

`rows-affected = 0` ⇒ insufficient funds ⇒ record `DECLINED`, no balance change. The credit is a matching atomic `UPDATE` in the **same transaction**, so debit+credit net to zero and total balance is invariant. No read-modify-write in app code, so it's correct under any concurrency on one wallet.

**Deadlock:** a transfer holds two row locks. Locking in request order deadlocks under A→B + B→A (observed `deadlock detected` live under load → HTTP 500s). Fixed by locking both rows in a deterministic order:

```sql
SELECT * FROM wallets WHERE id IN (:a, :b) ORDER BY id FOR UPDATE;
```

Every transfer takes the **lower id first**, so no cycle; verified zero 5xx afterward.

**Rejected:** `SERIALIZABLE` isolation (retry-on-serialization plumbing for an invariant a `CHECK` already guarantees); a per-wallet application lock/queue (a whole coordination layer to build and keep correct).

## Where idempotency lives
In `transfers`: `idempotency_key` is **UNIQUE** and **claimed in the same transaction as the ledger write** via `INSERT … ON CONFLICT (idempotency_key) DO NOTHING`. Winning the insert (1 row) grants exclusive ownership; only then do I debit/credit. A concurrent/prior writer gets `rows-affected = 0` **without aborting the Postgres transaction** (the reason for `ON CONFLICT` over catch-a-unique-violation, which poisons the tx), and is served the original result as a replay. Same key + **different body** → detected via `request_hash` → **`409`**. The same `ON CONFLICT` pattern makes `POST /wallets` race-free.

## Consistency vs availability
Chose **consistency**: a single authoritative Postgres, strongly-consistent reads/writes, transactional invariants. Consciously gave up horizontal **write** scaling and DB-partition tolerance — the app is stateless and scales out, but correctness funnels through one Postgres. For money, brief unavailability beats a double-spend.

## AI: directed vs decided
This build was **AI-assisted end to end** — an agentic coding tool wrote essentially all of the code, SQL, tests, and the first draft of this reasoning. I want to be honest about that rather than overstate my authorship.

- **What I directed** (I set the intent, the AI implemented): the choice to attempt the wallet exercise; the requirement that correctness be enforced in the database rather than app code; the decision to deploy on a free tier (Railway first, then Render + managed Postgres) and to get a genuinely reachable public URL; and pushing to keep iterating whenever a deploy or a test failed instead of accepting a broken state.
- **What I let the AI decide** (I reviewed and accepted its design): the specific mechanisms — atomic conditional debit, `ON CONFLICT` key-claim, `request_hash` for same-key/different-body, sorted `FOR UPDATE` for deadlock-freedom — plus all the boilerplate (Spring wiring, DTOs, logback JSON config, Dockerfile, migrations, burst script).
- **The most instructive moment:** the AI's initial design *claimed* to be deadlock-free without explicit locking. That was wrong. Under the live A→B + B→A contention burst it deadlocked (`ERROR: deadlock detected` in the Postgres logs, surfacing as HTTP 500s). The `burst.sh` "no 5xx under contention" assertion caught it, and the fix was deterministic sorted-order `FOR UPDATE` locking. I understand *why* that works — a fixed global lock order removes the circular wait — and can walk through it, the `ON CONFLICT`-in-same-transaction idempotency, and the conditional-debit no-overdraft guarantee. Where my depth is shallower and I'd defer/learn is the finer Postgres locking and isolation internals beyond this design.

## Free-tier cost note
**₹0.** Deployed on Render (free Docker web service) + Render free managed Postgres; no card required. Everything runs on free tiers.
