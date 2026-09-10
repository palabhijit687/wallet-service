package com.paytm.pml.wallet.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters exposed alongside the standard request-rate / latency / error
 * metrics that Spring Boot Actuator + Micrometer emit automatically.
 * Scraped at /actuator/prometheus (also aliased to /metrics).
 */
@Component
public class WalletMetrics {

    private final Counter walletsCreated;
    private final Counter transfersCreated;
    private final Counter transfersReversed;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter idempotencyConflicts;

    public WalletMetrics(MeterRegistry registry) {
        this.walletsCreated = Counter.builder("wallet_wallets_created_total")
                .description("Wallets created (get-or-create that actually inserted)")
                .register(registry);
        this.transfersCreated = Counter.builder("wallet_transfers_created_total")
                .description("Transfers that succeeded (one debit + one credit committed)")
                .register(registry);
        this.transfersReversed = Counter.builder("wallet_transfers_reversed_total")
                .description("Reversals that succeeded (money refunded to the original sender)")
                .register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("wallet_transfers_declined_total")
                .tag("reason", "insufficient_funds")
                .description("Transfers declined for insufficient funds")
                .register(registry);
        this.idempotentReplays = Counter.builder("wallet_idempotent_replays_total")
                .description("Transfer requests served from an existing idempotency key")
                .register(registry);
        this.idempotencyConflicts = Counter.builder("wallet_idempotency_conflicts_total")
                .description("Same idempotency key reused with a different body (409)")
                .register(registry);
    }

    public void walletCreated() {
        walletsCreated.increment();
    }

    public void transferCreated() {
        transfersCreated.increment();
    }

    public void transferReversed() {
        transfersReversed.increment();
    }

    public void transferDeclinedInsufficientFunds() {
        transfersDeclinedInsufficientFunds.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void idempotencyConflict() {
        idempotencyConflicts.increment();
    }
}
