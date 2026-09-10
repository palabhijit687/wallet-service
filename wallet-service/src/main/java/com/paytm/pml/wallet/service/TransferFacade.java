package com.paytm.pml.wallet.service;

import com.paytm.pml.wallet.domain.Transfer;
import com.paytm.pml.wallet.repo.TransferRepository;
import com.paytm.pml.wallet.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Wraps TransferService so a ReplayCollision (a concurrent same-key writer won
 * the unique-constraint race) is resolved AFTER our transaction has rolled back.
 * We re-read the committed winner in a fresh read-only transaction and return it
 * as a replay -- so the retry storm converges to one debit/credit and identical
 * responses for every caller.
 */
@Service
public class TransferFacade {

    private final TransferService transferService;
    private final TransferRepository transfers;

    public TransferFacade(TransferService transferService, TransferRepository transfers) {
        this.transferService = transferService;
        this.transfers = transfers;
    }

    public TransferService.TransferResult transfer(String requestedBy, UUID fromId, UUID toId,
                                                   long amountPaise, String idempotencyKey) {
        try {
            return transferService.transfer(requestedBy, fromId, toId, amountPaise, idempotencyKey);
        } catch (TransferService.ReplayCollision collision) {
            return resolveWinner(collision);
        }
    }

    public TransferService.TransferResult reverse(UUID originalId, String requestedBy, String idempotencyKey) {
        try {
            return transferService.reverse(originalId, requestedBy, idempotencyKey);
        } catch (TransferService.ReplayCollision collision) {
            return resolveWinner(collision);
        }
    }

    public com.paytm.pml.wallet.domain.Transfer get(UUID id) {
        return transferService.get(id);
    }

    @Transactional(readOnly = true)
    protected TransferService.TransferResult resolveWinner(TransferService.ReplayCollision collision) {
        Transfer winner = transfers.findByIdempotencyKey(collision.getIdempotencyKey())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "transfer_failed", "collision without a committed winner"));
        if (!winner.getRequestHash().equals(collision.getRequestHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict",
                    "idempotency_key reused with a different request body");
        }
        return new TransferService.TransferResult(winner, true);
    }
}
