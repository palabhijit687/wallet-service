#!/usr/bin/env bash
#
# One-command burst script for the Wallet & P2P Transfer service.
# Reproduces the three live probes from the brief and asserts the invariants:
#
#   1. Concurrent get-or-create  -> exactly one wallet for a brand-new user
#   2. Idempotent retry storm     -> one debit/credit, identical responses
#   3. Conservation under contention (incl. A->B and B->A at once)
#                                 -> total balance unchanged, no negative balance
#
# Usage:
#   ./burst.sh [BASE_URL]
#   BASE_URL defaults to http://localhost:8080
#
# Requires: bash, curl, and python3 (for JSON parsing + assertions).
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
TOK_ALICE="${TOK_ALICE:-tok_alice}"
TOK_BOB="${TOK_BOB:-tok_bob}"

CONCURRENCY="${CONCURRENCY:-50}"          # N for the get-or-create + retry storms
CONTENTION_TRANSFERS="${CONTENTION_TRANSFERS:-200}"

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
hdr()  { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

FAILED=0
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# json <key> reads a top-level field from a JSON blob on stdin (tolerates non-JSON).
json() {
  python3 -c "import sys,json
try:
    print(json.load(sys.stdin).get('$1',''))
except Exception:
    print('')"
}

api() {
  # api METHOD PATH TOKEN [BODY]
  local method="$1" path="$2" token="$3" body="${4:-}"
  if [[ -n "$body" ]]; then
    curl -fsS -X "$method" "$BASE_URL$path" \
      -H "Authorization: Bearer $token" \
      -H "Content-Type: application/json" \
      -d "$body"
  else
    curl -fsS -X "$method" "$BASE_URL$path" \
      -H "Authorization: Bearer $token"
  fi
}

wait_ready() {
  hdr "Waiting for $BASE_URL to be ready"
  for _ in $(seq 1 60); do
    if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
      pass "service is up"
      return 0
    fi
    sleep 1
  done
  fail "service never became ready"
  exit 1
}

# ---------------------------------------------------------------------------
# Probe 1: concurrent get-or-create for a brand-new user -> exactly one wallet
# ---------------------------------------------------------------------------
probe_get_or_create() {
  hdr "Probe 1: concurrent get-or-create ($CONCURRENCY simultaneous POST /wallets, new user)"
  # alice is a brand-new user for this run (the DB may already have her from a
  # prior run; the invariant is still "exactly one wallet", so we just assert all
  # responses agree on a single id).
  : > "$WORK/wallet_ids"
  for _ in $(seq 1 "$CONCURRENCY"); do
    ( api POST /wallets "$TOK_ALICE" '{"initial_balance_paise": 100000}' \
        | json id >> "$WORK/wallet_ids" ) &
  done
  wait

  local distinct
  distinct=$(sort -u "$WORK/wallet_ids" | grep -c . || true)
  if [[ "$distinct" -eq 1 ]]; then
    pass "all $CONCURRENCY concurrent creates returned one wallet id ($(sort -u "$WORK/wallet_ids"))"
  else
    fail "expected 1 distinct wallet id, got $distinct"
    sort -u "$WORK/wallet_ids"
  fi
  ALICE_ID=$(sort -u "$WORK/wallet_ids" | head -1)
}

# ---------------------------------------------------------------------------
# Probe 2: idempotent retry storm -> one debit/credit, identical responses
# ---------------------------------------------------------------------------
probe_idempotent_storm() {
  hdr "Probe 2: idempotent retry storm ($CONCURRENCY concurrent transfers, same key)"
  BOB_ID=$(api POST /wallets "$TOK_BOB" '{"initial_balance_paise": 0}' | json id)

  local before_alice before_bob
  before_alice=$(api GET "/wallets/$ALICE_ID" "$TOK_ALICE" | json balance_paise)
  before_bob=$(api GET "/wallets/$BOB_ID" "$TOK_BOB" | json balance_paise)

  local key="storm-$(date +%s)-$RANDOM"
  local amount=1500
  local body="{\"from\":\"$ALICE_ID\",\"to\":\"$BOB_ID\",\"amount_paise\":$amount,\"idempotency_key\":\"$key\"}"

  : > "$WORK/storm_ids"
  for _ in $(seq 1 "$CONCURRENCY"); do
    ( api POST /transfers "$TOK_ALICE" "$body" | json id >> "$WORK/storm_ids" ) &
  done
  wait

  local distinct_transfers after_alice after_bob
  distinct_transfers=$(sort -u "$WORK/storm_ids" | grep -c . || true)
  after_alice=$(api GET "/wallets/$ALICE_ID" "$TOK_ALICE" | json balance_paise)
  after_bob=$(api GET "/wallets/$BOB_ID" "$TOK_BOB" | json balance_paise)

  [[ "$distinct_transfers" -eq 1 ]] \
    && pass "all $CONCURRENCY retries resolved to one transfer id" \
    || fail "expected 1 transfer id, got $distinct_transfers"

  [[ "$after_alice" -eq $((before_alice - amount)) ]] \
    && pass "sender debited exactly once ($before_alice -> $after_alice)" \
    || fail "sender debited wrong: $before_alice -> $after_alice (expected -$amount)"

  [[ "$after_bob" -eq $((before_bob + amount)) ]] \
    && pass "receiver credited exactly once ($before_bob -> $after_bob)" \
    || fail "receiver credited wrong: $before_bob -> $after_bob (expected +$amount)"

  # same key + different body -> 409
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TOK_ALICE" -H "Content-Type: application/json" \
    -d "{\"from\":\"$ALICE_ID\",\"to\":\"$BOB_ID\",\"amount_paise\":9999,\"idempotency_key\":\"$key\"}")
  [[ "$code" == "409" ]] \
    && pass "same key + different body -> 409" \
    || fail "same key + different body expected 409, got $code"
}

# ---------------------------------------------------------------------------
# Probe 3: conservation under contention (incl. A->B and B->A at once)
# ---------------------------------------------------------------------------
probe_conservation() {
  hdr "Probe 3: conservation under contention ($CONTENTION_TRANSFERS concurrent transfers, both directions)"
  local a b total_before
  a=$(api GET "/wallets/$ALICE_ID" "$TOK_ALICE" | json balance_paise)
  b=$(api GET "/wallets/$BOB_ID" "$TOK_BOB" | json balance_paise)
  total_before=$((a + b))
  echo "  start: alice=$a bob=$b total=$total_before"

  for i in $(seq 1 "$CONTENTION_TRANSFERS"); do
    local key="cont-$(date +%s)-$i-$RANDOM"
    if (( i % 2 == 0 )); then
      # A -> B
      ( api POST /transfers "$TOK_ALICE" \
          "{\"from\":\"$ALICE_ID\",\"to\":\"$BOB_ID\",\"amount_paise\":100,\"idempotency_key\":\"$key\"}" \
          >/dev/null 2>&1 || true ) &
    else
      # B -> A  (opposite direction at the same time -> deadlock-order stress)
      ( api POST /transfers "$TOK_BOB" \
          "{\"from\":\"$BOB_ID\",\"to\":\"$ALICE_ID\",\"amount_paise\":100,\"idempotency_key\":\"$key\"}" \
          >/dev/null 2>&1 || true ) &
    fi
    # cap in-flight fan-out so we don't exhaust local fds
    if (( i % 50 == 0 )); then wait; fi
  done
  wait

  local a2 b2 total_after
  a2=$(api GET "/wallets/$ALICE_ID" "$TOK_ALICE" | json balance_paise)
  b2=$(api GET "/wallets/$BOB_ID" "$TOK_BOB" | json balance_paise)
  total_after=$((a2 + b2))
  echo "  end:   alice=$a2 bob=$b2 total=$total_after"

  [[ "$total_after" -eq "$total_before" ]] \
    && pass "conservation held: total unchanged ($total_before)" \
    || fail "conservation broken: $total_before -> $total_after"

  { [[ "$a2" -ge 0 ]] && [[ "$b2" -ge 0 ]]; } \
    && pass "no negative balances (alice=$a2, bob=$b2)" \
    || fail "a balance went negative (alice=$a2, bob=$b2)"
}

# ---------------------------------------------------------------------------
# Probe 4 (R3): reversal / refund — exactly-once, conserving, no double-refund
# ---------------------------------------------------------------------------
probe_reversal() {
  hdr "Probe 4 (R3): reversal / refund"
  local a_before b_before total_before
  a_before=$(api GET "/wallets/$ALICE_ID" "$TOK_ALICE" | json balance_paise)
  b_before=$(api GET "/wallets/$BOB_ID" "$TOK_BOB" | json balance_paise)
  total_before=$((a_before + b_before))

  # a fresh transfer to reverse
  local tkey="rev-orig-$(date +%s)-$RANDOM"
  local amount=2000
  local tid
  tid=$(api POST /transfers "$TOK_ALICE" \
    "{\"from\":\"$ALICE_ID\",\"to\":\"$BOB_ID\",\"amount_paise\":$amount,\"idempotency_key\":\"$tkey\"}" | json id)

  # fire the reversal K times concurrently with the SAME key
  local rkey="rev-key-$(date +%s)-$RANDOM"
  : > "$WORK/rev_ids"
  for _ in $(seq 1 "$CONCURRENCY"); do
    ( api POST "/transfers/$tid/reverse" "$TOK_BOB" "{\"idempotency_key\":\"$rkey\"}" \
        2>/dev/null | json id >> "$WORK/rev_ids" ) &
  done
  wait

  local distinct a_after b_after total_after
  distinct=$(sort -u "$WORK/rev_ids" | grep -c . || true)
  a_after=$(api GET "/wallets/$ALICE_ID" "$TOK_ALICE" | json balance_paise)
  b_after=$(api GET "/wallets/$BOB_ID" "$TOK_BOB" | json balance_paise)
  total_after=$((a_after + b_after))

  [[ "$distinct" -eq 1 ]] \
    && pass "concurrent same-key reverse -> exactly one reversal" \
    || fail "expected 1 reversal, got $distinct"

  [[ "$total_after" -eq "$total_before" ]] \
    && pass "conservation held across transfer+reversal (total $total_before)" \
    || fail "conservation broken: $total_before -> $total_after"

  { [[ "$a_after" -eq "$a_before" ]] && [[ "$b_after" -eq "$b_before" ]]; } \
    && pass "money fully returned (alice $a_before, bob $b_before restored)" \
    || fail "balances not restored: alice $a_before->$a_after, bob $b_before->$b_after"

  # reversing an already-reversed transfer with a NEW key -> clean 409
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/transfers/$tid/reverse" \
    -H "Authorization: Bearer $TOK_BOB" -H "Content-Type: application/json" \
    -d "{\"idempotency_key\":\"rev-again-$RANDOM\"}")
  [[ "$code" == "409" ]] \
    && pass "already-reversed (new key) -> 409, no second refund" \
    || fail "already-reversed expected 409, got $code"
}

wait_ready
probe_get_or_create
probe_idempotent_storm
probe_conservation
probe_reversal

hdr "Domain metrics snapshot"
curl -fsS "$BASE_URL/metrics" 2>/dev/null | grep -E '^wallet_' || echo "  (metrics endpoint not reachable)"

echo
if [[ "$FAILED" -eq 0 ]]; then
  printf '\033[32mALL PROBES PASSED\033[0m\n'
  exit 0
else
  printf '\033[31mSOME PROBES FAILED\033[0m\n'
  exit 1
fi
