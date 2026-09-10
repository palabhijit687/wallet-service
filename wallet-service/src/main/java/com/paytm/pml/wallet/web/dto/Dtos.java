package com.paytm.pml.wallet.web.dto;

import com.paytm.pml.wallet.domain.Transfer;
import com.paytm.pml.wallet.domain.Wallet;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Request/response payloads. Money is integer paise everywhere.
 */
public final class Dtos {

    private Dtos() {
    }

    // --- Wallet ---

    public record CreateWalletRequest(
            // Optional starting balance in paise; defaults to 0. Handy for demos/tests.
            Long initialBalancePaise) {
    }

    public record WalletResponse(UUID id, String owner, long balancePaise) {
        public static WalletResponse of(Wallet w) {
            return new WalletResponse(w.getId(), w.getOwnerUser(), w.getBalancePaise());
        }
    }

    // --- Transfer ---

    public record CreateTransferRequest(
            @NotNull(message = "from is required") UUID from,
            @NotNull(message = "to is required") UUID to,
            @Positive(message = "amount_paise must be a positive integer") long amountPaise,
            @NotNull(message = "idempotency_key is required") String idempotencyKey) {
    }

    public record ReverseTransferRequest(
            @NotNull(message = "idempotency_key is required") String idempotencyKey) {
    }

    public record TransferResponse(
            UUID id,
            UUID from,
            UUID to,
            long amountPaise,
            String status,
            String declineReason,
            UUID reversalOf,
            boolean idempotentReplay) {

        public static TransferResponse of(Transfer t, boolean replay) {
            return new TransferResponse(
                    t.getId(),
                    t.getFromWallet(),
                    t.getToWallet(),
                    t.getAmountPaise(),
                    t.getStatus().name(),
                    t.getDeclineReason(),
                    t.getReversalOf(),
                    replay);
        }
    }
}
