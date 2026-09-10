package com.paytm.pml.wallet.repo;

import com.paytm.pml.wallet.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByOwnerUser(String ownerUser);

    /**
     * Lock two wallet rows FOR UPDATE in a DETERMINISTIC order (sorted by id via
     * ORDER BY), regardless of which is the sender or receiver. Because every
     * transfer -- A->B and B->A alike -- acquires the lower id first, no two
     * transactions can form a circular wait, so the opposite-direction burst
     * cannot deadlock. Returns the locked rows (existence check happens here too).
     */
    @Query(value = """
            SELECT * FROM wallets
             WHERE id IN (:a, :b)
             ORDER BY id
             FOR UPDATE
            """, nativeQuery = true)
    java.util.List<Wallet> lockTwoInOrder(@Param("a") UUID a, @Param("b") UUID b);

    /**
     * Race-free get-or-create in one statement. INSERT ... ON CONFLICT DO NOTHING
     * on the unique owner_user constraint: exactly one concurrent insert wins, the
     * losers affect 0 rows WITHOUT aborting the transaction (unlike catching a
     * unique violation, which poisons a Postgres tx). Caller then re-SELECTs.
     */
    @Modifying
    @Query(value = """
            INSERT INTO wallets (id, owner_user, balance_paise, created_at, updated_at)
            VALUES (:id, :ownerUser, :balance, now(), now())
            ON CONFLICT (owner_user) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("ownerUser") String ownerUser,
                       @Param("balance") long balance);

    /**
     * Atomic conditional debit. Returns rows-affected: 1 if the debit applied,
     * 0 if the balance was insufficient. This is the no-overdraft guard done in
     * a single statement -- no read-modify-write in app code, so it stays correct
     * under any amount of concurrency touching the same wallet.
     */
    @Modifying
    @Query(value = """
            UPDATE wallets
               SET balance_paise = balance_paise - :amount,
                   updated_at = now()
             WHERE id = :id
               AND balance_paise >= :amount
            """, nativeQuery = true)
    int tryDebit(@Param("id") UUID id, @Param("amount") long amount);

    /**
     * Atomic credit. Always applies (no upper bound on a balance).
     */
    @Modifying
    @Query(value = """
            UPDATE wallets
               SET balance_paise = balance_paise + :amount,
                   updated_at = now()
             WHERE id = :id
            """, nativeQuery = true)
    int credit(@Param("id") UUID id, @Param("amount") long amount);
}
