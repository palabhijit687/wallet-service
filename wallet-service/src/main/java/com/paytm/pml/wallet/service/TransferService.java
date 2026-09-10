package com.paytm.pml.wallet.service;

import com.paytm.pml.wallet.domain.Transfer;
import com.paytm.pml.wallet.metrics.WalletMetrics;
import com.paytm.pml.wallet.repo.TransferRepository;
import com.paytm.pml.wallet.repo.WalletRepository;
import com.paytm.pml.wallet.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository wallets;
    private final TransferRepository transfers;
    private final WalletMetrics metrics;

    public TransferService(WalletRepository wallets, TransferRepository transfers, WalletMetrics metrics) {
        this.wallets = wallets;
        this.transfers = transfers;
        this.metrics = metrics;
    }

    /**
     * Move money from one wallet to another, exactly once per idempotency key.
     *
     * Correctness model (all inside ONE DB transaction, no tx-aborting exceptions):
     *  - Exactly-once: we CLAIM the key first by inserting the transfers row with
     *    ON CONFLICT (idempotency_key) DO NOTHING. Winning the insert (1 row) gives
     *    this transaction exclusive ownership of the key; only then do we touch the
     *    ledger. A concurrent/prior writer makes the insert affect 0 rows (without
     *    poisoning the tx), so we treat it as a replay and never double-apply.
     *  - Conservation + no-overdraft: the debit is an atomic conditional UPDATE
     *    (balance = balance - amount WHERE balance >= amount). rows-affected = 0
     *    means insufficient funds -> we finalize the claimed row as DECLINED and
     *    commit no balance change. The credit is a plain atomic UPDATE of equal
     *    magnitude, so the sum of balances is invariant.
     *  - Deadlock-free: both wallet rows are locked FOR UPDATE in a DETERMINISTIC
     *    order (sorted by id) at the start, so A->B and B->A always acquire the
     *    lower id first and cannot form a circular wait.
     */
    @Transactional
    public TransferResult transfer(String requestedBy, UUID fromId, UUID toId,
                                   long amountPaise, String idempotencyKey) {
        validate(fromId, toId, amountPaise);
        String requestHash = fingerprint("transfer", fromId, toId, amountPaise);
        MDC.put("idempotency_key", idempotencyKey);
        MDC.put("amount_paise", Long.toString(amountPaise));

        // Fast path: key already committed by an earlier request -> replay/conflict.
        var prior = transfers.findByIdempotencyKey(idempotencyKey);
        if (prior.isPresent()) {
            return resolveReplay(prior.get(), requestHash, idempotencyKey);
        }

        // Lock BOTH wallets in deterministic id order before touching balances.
        // This is the deadlock-avoidance guarantee: A->B and B->A both lock the
        // lower id first, so opposite-direction transfers can't form a lock cycle.
        // It also confirms both wallets exist (clean 404 otherwise).
        if (wallets.lockTwoInOrder(fromId, toId).size() != 2) {
            throw new ApiException(HttpStatus.NOT_FOUND, "wallet_not_found",
                    "one or both wallets do not exist");
        }

        // Claim the key. If we don't win the insert, a concurrent writer owns it;
        // re-read and replay (their committed result once their tx lands).
        UUID transferId = UUID.randomUUID();
        int claimed = transfers.insertIfAbsent(transferId, idempotencyKey, requestedBy, fromId, toId,
                amountPaise, "PENDING", null, requestHash);
        if (claimed == 0) {
            return awaitConcurrentWinner(idempotencyKey, requestHash);
        }

        // We own the key + hold both row locks. Move the money, then finalize.
        int debited = wallets.tryDebit(fromId, amountPaise);
        if (debited == 0) {
            transfers.finalizeStatus(transferId, "DECLINED", "INSUFFICIENT_FUNDS");
            metrics.transferDeclinedInsufficientFunds();
            MDC.put("event", "transfer_declined");
            MDC.put("transfer_id", transferId.toString());
            log.info("transfer declined insufficient_funds from={} to={} amount={}", fromId, toId, amountPaise);
            return new TransferResult(transfers.findById(transferId).orElseThrow(), false);
        }

        wallets.credit(toId, amountPaise);
        transfers.finalizeStatus(transferId, "SUCCEEDED", null);
        metrics.transferCreated();
        MDC.put("event", "transfer_succeeded");
        MDC.put("transfer_id", transferId.toString());
        log.info("transfer succeeded from={} to={} amount={}", fromId, toId, amountPaise);
        return new TransferResult(transfers.findById(transferId).orElseThrow(), false);
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID id) {
        return transfers.findById(id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "transfer_not_found", "no transfer with id " + id));
    }

    /**
     * Reverse (refund) a prior successful transfer: move the exact amount back from
     * the original recipient to the original sender. A reversal is itself a transfer
     * row (reusing the same atomic conditional-debit primitive with roles swapped),
     * so it inherits conservation and no-overdraft.
     *
     * Guards:
     *  - Exactly-once per reversal: its own idempotency_key claim (ON CONFLICT), AND
     *    a partial unique index (one reversal per original) — a concurrent
     *    double-reverse with different keys still cannot double-refund because we
     *    lock the original row FOR UPDATE and check for an existing reversal.
     *  - Only a SUCCEEDED, non-reversal transfer can be reversed (404/409 otherwise).
     *  - Recipient-insufficient-funds: the recipient may have already spent the
     *    money. We decline cleanly (DECLINED / INSUFFICIENT_FUNDS) rather than let a
     *    balance go negative — money is never created.
     */
    @Transactional
    public TransferResult reverse(UUID originalId, String requestedBy, String idempotencyKey) {
        MDC.put("idempotency_key", idempotencyKey);

        // Idempotent replay of the SAME reversal key.
        var priorByKey = transfers.findByIdempotencyKey(idempotencyKey);
        if (priorByKey.isPresent()) {
            Transfer p = priorByKey.get();
            if (p.getReversalOf() == null || !p.getReversalOf().equals(originalId)) {
                metrics.idempotencyConflict();
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict",
                        "idempotency_key reused for a different reversal");
            }
            metrics.idempotentReplay();
            MDC.put("event", "reversal_replay");
            MDC.put("transfer_id", p.getId().toString());
            log.info("reversal replay served for key={}", idempotencyKey);
            return new TransferResult(p, true);
        }

        // Lock the original so concurrent reverses of it serialize.
        Transfer original = transfers.lockById(originalId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "transfer_not_found", "no transfer with id " + originalId));

        if (original.getReversalOf() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "not_reversible",
                    "a reversal cannot itself be reversed");
        }
        if (original.getStatus() != Transfer.Status.SUCCEEDED) {
            throw new ApiException(HttpStatus.CONFLICT, "not_reversible",
                    "only a SUCCEEDED transfer can be reversed (status=" + original.getStatus() + ")");
        }
        var existingReversal = transfers.findByReversalOf(originalId);
        if (existingReversal.isPresent()) {
            // Already reversed by a DIFFERENT key -> not a replay of this key.
            throw new ApiException(HttpStatus.CONFLICT, "already_reversed",
                    "transfer " + originalId + " has already been reversed");
        }

        // Reversal moves money back: recipient -> sender.
        UUID from = original.getToWallet();
        UUID to = original.getFromWallet();
        long amount = original.getAmountPaise();
        String requestHash = fingerprint("reverse", from, to, amount);

        // Lock both wallets in deterministic id order (same rule as transfer), so a
        // reversal and a concurrent transfer over the same pair cannot deadlock.
        wallets.lockTwoInOrder(from, to);

        UUID reversalId = UUID.randomUUID();
        int claimed = transfers.insertReversalIfAbsent(reversalId, idempotencyKey, requestedBy,
                from, to, amount, "PENDING", null, requestHash, originalId);
        if (claimed == 0) {
            // Lost the key race; re-read and replay.
            return awaitConcurrentWinner(idempotencyKey, requestHash);
        }

        int debited = wallets.tryDebit(from, amount);
        if (debited == 0) {
            transfers.finalizeStatus(reversalId, "DECLINED", "INSUFFICIENT_FUNDS");
            metrics.transferDeclinedInsufficientFunds();
            MDC.put("event", "reversal_declined");
            MDC.put("transfer_id", reversalId.toString());
            log.info("reversal declined insufficient_funds original={} amount={}", originalId, amount);
            return new TransferResult(transfers.findById(reversalId).orElseThrow(), false);
        }

        wallets.credit(to, amount);
        transfers.finalizeStatus(reversalId, "SUCCEEDED", null);
        // Mark the original as REVERSED for clarity (its money is fully returned).
        transfers.finalizeStatus(originalId, "REVERSED", null);
        metrics.transferReversed();
        MDC.put("event", "reversal_succeeded");
        MDC.put("transfer_id", reversalId.toString());
        log.info("reversal succeeded original={} amount={}", originalId, amount);
        return new TransferResult(transfers.findById(reversalId).orElseThrow(), false);
    }

    /**
     * We lost the insert race for this key. The winner may still be committing, so
     * re-read; if not yet visible, signal the facade to resolve it after our tx.
     */
    private TransferResult awaitConcurrentWinner(String idempotencyKey, String requestHash) {
        var winner = transfers.findByIdempotencyKey(idempotencyKey);
        if (winner.isPresent()) {
            return resolveReplay(winner.get(), requestHash, idempotencyKey);
        }
        throw new ReplayCollision(idempotencyKey, requestHash);
    }

    private TransferResult resolveReplay(Transfer original, String requestHash, String idempotencyKey) {
        if (!original.getRequestHash().equals(requestHash)) {
            metrics.idempotencyConflict();
            MDC.put("event", "idempotency_conflict");
            MDC.put("idempotency_key", idempotencyKey);
            log.info("idempotency key reused with different body key={}", idempotencyKey);
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict",
                    "idempotency_key reused with a different request body");
        }
        metrics.idempotentReplay();
        MDC.put("event", "idempotent_replay");
        MDC.put("idempotency_key", idempotencyKey);
        MDC.put("transfer_id", original.getId().toString());
        log.info("idempotent replay served for key={}", idempotencyKey);
        return new TransferResult(original, true);
    }

    private void validate(UUID fromId, UUID toId, long amountPaise) {
        if (amountPaise <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_amount",
                    "amount_paise must be a positive integer");
        }
        if (fromId.equals(toId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "same_wallet",
                    "from and to must be different wallets");
        }
    }

    private String fingerprint(String kind, UUID fromId, UUID toId, long amountPaise) {
        String raw = kind + "|" + fromId + "|" + toId + "|" + amountPaise;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Internal signal that a concurrent same-key writer won; handled by the caller layer. */
    public static class ReplayCollision extends RuntimeException {
        private final String idempotencyKey;
        private final String requestHash;

        public ReplayCollision(String idempotencyKey, String requestHash) {
            this.idempotencyKey = idempotencyKey;
            this.requestHash = requestHash;
        }

        public String getIdempotencyKey() {
            return idempotencyKey;
        }

        public String getRequestHash() {
            return requestHash;
        }
    }

    public record TransferResult(Transfer transfer, boolean replay) {
    }
}
