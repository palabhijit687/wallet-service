# Wallet & P2P Transfer

Paytm PML — R2 Agentic Exercise (Deploy & Reason): the lead exercise from the bank.
A small wallet service with peer-to-peer transfers that stays **correct under
concurrency and failure** — conservation, no overdraft, exactly-once transfers,
and race-free get-or-create — built with Spring Boot 3 + Postgres.

Money is always **integer paise**. Never floats, never rupees-as-decimal.

---

## API

All business endpoints require a bearer token (`Authorization: Bearer <token>`).
Tokens map to users via `WALLET_TOKENS` (default `tok_alice:alice,tok_bob:bob,tok_carol:carol`).

| Method | Path              | Body                                              | Notes |
|--------|-------------------|---------------------------------------------------|-------|
| POST   | `/wallets`        | `{"initial_balance_paise": 100000}` (optional)    | Get-or-create the caller's wallet. Idempotent per user. |
| GET    | `/wallets/{id}`   | —                                                 | Current balance. |
| POST   | `/transfers`      | `{"from","to","amount_paise","idempotency_key"}`  | Move money. Exactly-once per key. |
| GET    | `/transfers/{id}` | —                                                 | Transfer status (`SUCCEEDED` / `DECLINED` / `REVERSED`). |
| POST   | `/transfers/{id}/reverse` | `{"idempotency_key"}`                     | Refund a prior successful transfer back to the sender. Exactly-once. |

Open (no auth): `/actuator/health`, `/actuator/prometheus`, `/metrics`.

### Example

```bash
# create two wallets
ALICE=$(curl -s -XPOST localhost:8080/wallets -H 'Authorization: Bearer tok_alice' \
  -H 'Content-Type: application/json' -d '{"initial_balance_paise":100000}' | jq -r .id)
BOB=$(curl -s -XPOST localhost:8080/wallets -H 'Authorization: Bearer tok_bob' \
  -H 'Content-Type: application/json' -d '{"initial_balance_paise":0}' | jq -r .id)

# transfer ₹15.00 (1500 paise), idempotent
curl -s -XPOST localhost:8080/transfers -H 'Authorization: Bearer tok_alice' \
  -H 'Content-Type: application/json' \
  -d "{\"from\":\"$ALICE\",\"to\":\"$BOB\",\"amount_paise\":1500,\"idempotency_key\":\"demo-1\"}"
```

---

## Run it

### Local (Docker Compose — app + Postgres, one command)

```bash
docker compose up --build
# service on http://localhost:8080
```

### Burst script (reproduces the three live probes)

With the service running:

```bash
./burst.sh                       # against http://localhost:8080
./burst.sh https://your-app.url  # against a deployed URL
```

It asserts:
1. **Concurrent get-or-create** — N simultaneous `POST /wallets` for a new user → exactly one wallet.
2. **Idempotent retry storm** — same transfer key fired N times concurrently → one debit/credit, identical responses, and same-key/different-body → `409`.
3. **Conservation under contention** — many concurrent transfers, including A→B and B→A at once → total balance unchanged, no negative balance.
4. **Reversal / refund (R3)** — a transfer is reversed N times concurrently with the same key → exactly one refund, total returns to the pre-transfer sum, and reversing an already-reversed transfer with a new key → `409` (no double-refund).

Requires `bash`, `curl`, `python3`.

---

## Deploy (free tier, ₹0)

The image is a plain container listening on `$PORT` (default 8080) and needs a
Postgres URL. On **Render / Railway / Fly.io / Koyeb**:

1. Provision a **free managed Postgres**.
2. Deploy this repo as a Docker service. Set env:
   - `DB_URL=jdbc:postgresql://<host>:<port>/<db>`
   - `DB_USER`, `DB_PASSWORD`
   - `WALLET_TOKENS=tok_alice:alice,tok_bob:bob` (or your own)
3. Health check path: `/actuator/health/readiness`.

Flyway runs the schema migration on boot, so the DB needs no manual setup.

---

## Observability

- **Logs**: structured JSON to stdout (one line per event) with a `correlation_id`
  per request and domain events: `wallet_created`, `wallet_create_race_resolved`,
  `transfer_succeeded`, `transfer_declined`, `idempotent_replay`, `idempotency_conflict`.
  The free host captures stdout and makes it publicly viewable.
- **Metrics**: `/metrics` (Prometheus, also `/actuator/prometheus`) — request rate
  and error rate (`http_server_requests_seconds_count` by `status`/`uri`) and a
  latency **histogram** (`http_server_requests_seconds_bucket`) so p99 is computed
  in Prometheus/Grafana with `histogram_quantile(0.99, ...)`. Plus domain counters:
  - `wallet_wallets_created_total`
  - `wallet_transfers_created_total`
  - `wallet_transfers_reversed_total`
  - `wallet_transfers_declined_total{reason="insufficient_funds"}`
  - `wallet_idempotent_replays_total`
  - `wallet_idempotency_conflicts_total`

---

## One-page write-up

### Data model
Two tables (see `V1__init.sql`, `V2__reversal.sql`):
- **`wallets`** — `id`, `owner_user` (**UNIQUE**), `balance_paise BIGINT` with a
  `CHECK (balance_paise >= 0)`.
- **`transfers`** — `id`, `idempotency_key` (**UNIQUE**), `from_wallet`, `to_wallet`,
  `amount_paise`, `status` (`PENDING`→`SUCCEEDED`/`DECLINED`, or `REVERSED`),
  `decline_reason`, `request_hash`, `reversal_of` (FK to the original transfer, with a
  **partial unique index** so each original is reversed at most once). Amount `> 0`,
  wallets distinct.

### Simplest-correct mechanism (conservation + no-overdraft)
An **atomic conditional debit**:

```sql
UPDATE wallets SET balance_paise = balance_paise - :amt
 WHERE id = :from AND balance_paise >= :amt;
```

`rows-affected = 0` ⇒ insufficient funds ⇒ record a `DECLINED` transfer, no balance
change. The credit is a plain atomic `UPDATE`. Debit and credit are the same
magnitude inside **one transaction**, so the sum of balances is invariant and no
balance can go negative (belt-and-suspenders with the `CHECK`). There is **no
read-modify-write in app code** — the database does the compare-and-decrement,
so it stays correct under any concurrency on the same wallet.

**Deadlock avoidance**: I deliberately avoid holding two row locks in
caller-supplied order. The debit and credit are independent single-row `UPDATE`s;
I never `SELECT … FOR UPDATE` two wallets, so A→B and B→A running at once cannot
form the classic opposite-order lock cycle. (If explicit locking were required I
would lock wallets in a fixed sorted order by id; the conditional `UPDATE` avoids
the explicit lock entirely, which is why it's the simplest correct thing here.)

**Heavier alternatives rejected**: `SELECT … FOR UPDATE` in sorted order (extra
round-trip + explicit lock ordering to reason about); `SERIALIZABLE` isolation
with retry loops (throughput cost + retry plumbing for a single-row invariant a
`CHECK` already guarantees); a per-wallet application lock or queue (a
coordination layer to build, deploy, and keep correct — more moving parts than
the one-statement guard needs).

### Where idempotency lives
In the `transfers` table: `idempotency_key` is **UNIQUE** and the row is written
**in the same transaction** as the debit/credit — I **claim the key first**:

```sql
INSERT INTO transfers (...) VALUES (...) ON CONFLICT (idempotency_key) DO NOTHING;
```

Winning the insert (1 row) grants this transaction exclusive ownership of the key;
only then do I touch the ledger, then finalize the row's status. A concurrent or
prior writer makes the insert affect **0 rows without aborting the transaction**,
so it's cleanly resolved as a replay. This ordering matters: catching a
unique-violation exception *after* debiting would poison the Postgres transaction
("current transaction is aborted"), so `ON CONFLICT DO NOTHING` (not
catch-and-continue) is the correct primitive. The same `ON CONFLICT` pattern makes
`POST /wallets` race-free.

- A retry storm with the same key → exactly one committed row; all others replay it.
- **Same key + different body** is detected via a `request_hash`
  (SHA-256 of `kind|from|to|amount`) stored on the row → `409 idempotency_conflict`.

### Reversal / refund (`POST /transfers/{id}/reverse`)
A reversal is **itself a transfer row** (roles swapped: recipient → sender),
reusing the exact same atomic conditional-debit primitive, so it inherits
conservation and no-overdraft for free. Exactly-once is doubly guarded: its own
`idempotency_key` claim **and** a partial unique index (`one reversal per
original`). Concurrent double-reverses serialize because the reversal path locks
the original row `FOR UPDATE` before checking-and-inserting. If the recipient has
already spent the money, the reversal **declines cleanly**
(`DECLINED`/`INSUFFICIENT_FUNDS`) rather than driving a balance negative — money is
never created.

### Consistency vs availability
This is money, so I chose **consistency** (single Postgres, strongly consistent
reads/writes, transactional invariants) over availability. What I consciously gave
up: horizontal write scaling and surviving a DB partition — the app is stateless
and scales out, but correctness funnels through one authoritative Postgres. For a
wallet, a brief unavailability is far preferable to a double-spend.

### AI: directed vs decided
- **Directed** (I chose the approach, AI typed): the correctness model —
  claim-key-first with `ON CONFLICT DO NOTHING` (so the Postgres tx never aborts),
  conditional-`UPDATE` debit, idempotency in the same transaction as the ledger
  write, `request_hash` for same-key/different-body, the reversal-as-a-transfer
  design with `FOR UPDATE` + partial-unique-index guards, deadlock-avoidance
  stance, and the burst probes.
- **Decided** (accepted the tool's design): boilerplate shapes — Spring wiring,
  DTO/record layout, logback JSON encoder config, Dockerfile layering.

### Verification
The four probes were run against **real Postgres 16** (not a mock): all pass —
race-free get-or-create (50-way), idempotent storm (50-way, one debit + `409` on
different body), conservation + no-overdraft (200-way both-direction), and R3
reversal (concurrent same-key → one refund, total conserved, already-reversed →
`409`, recipient-broke → clean decline). `src/test/.../InvariantsTest.java` runs the
same probes automatically via Testcontainers Postgres.

### Free-tier cost note
**₹0.** App container + free managed Postgres on Render/Railway/Fly/Koyeb; no card
required. Everything above runs on free tiers.
