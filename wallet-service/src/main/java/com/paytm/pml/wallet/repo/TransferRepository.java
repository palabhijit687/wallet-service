package com.paytm.pml.wallet.repo;

import com.paytm.pml.wallet.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    /**
     * Claim the idempotency key by inserting the transfer row FIRST, in the same
     * transaction as the (later) ledger movement. ON CONFLICT DO NOTHING means a
     * concurrent/prior writer with the same key makes this affect 0 rows without
     * aborting the tx -- the caller then treats it as a replay. Winning the insert
     * (1 row) grants exclusive ownership of the key for this transaction.
     */
    @Modifying
    @Query(value = """
            INSERT INTO transfers
                (id, idempotency_key, requested_by, from_wallet, to_wallet,
                 amount_paise, status, decline_reason, request_hash, created_at)
            VALUES
                (:id, :key, :requestedBy, :fromWallet, :toWallet,
                 :amount, :status, :declineReason, :requestHash, now())
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("key") String key,
                       @Param("requestedBy") String requestedBy, @Param("fromWallet") UUID fromWallet,
                       @Param("toWallet") UUID toWallet, @Param("amount") long amount,
                       @Param("status") String status, @Param("declineReason") String declineReason,
                       @Param("requestHash") String requestHash);

    /**
     * Finalize a claimed transfer once its ledger movement is decided.
     */
    @Modifying
    @Query(value = """
            UPDATE transfers
               SET status = :status, decline_reason = :declineReason
             WHERE id = :id
            """, nativeQuery = true)
    int finalizeStatus(@Param("id") UUID id, @Param("status") String status,
                       @Param("declineReason") String declineReason);

    /**
     * Lock the original transfer row for the duration of the tx. Serializes
     * concurrent reversals of the SAME original so the "already reversed?" check
     * and the reversal insert are atomic without relying on catching a constraint
     * violation (which would abort a Postgres tx).
     */
    @Query(value = "SELECT * FROM transfers WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Transfer> lockById(@Param("id") UUID id);

    /**
     * Find an existing reversal of the given original transfer, if any.
     */
    Optional<Transfer> findByReversalOf(UUID reversalOf);

    /**
     * Insert a reversal transfer row, claiming its own idempotency key. Same
     * ON CONFLICT DO NOTHING semantics as a normal transfer claim.
     */
    @Modifying
    @Query(value = """
            INSERT INTO transfers
                (id, idempotency_key, requested_by, from_wallet, to_wallet,
                 amount_paise, status, decline_reason, request_hash, reversal_of, created_at)
            VALUES
                (:id, :key, :requestedBy, :fromWallet, :toWallet,
                 :amount, :status, :declineReason, :requestHash, :reversalOf, now())
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertReversalIfAbsent(@Param("id") UUID id, @Param("key") String key,
                               @Param("requestedBy") String requestedBy, @Param("fromWallet") UUID fromWallet,
                               @Param("toWallet") UUID toWallet, @Param("amount") long amount,
                               @Param("status") String status, @Param("declineReason") String declineReason,
                               @Param("requestHash") String requestHash, @Param("reversalOf") UUID reversalOf);
}
