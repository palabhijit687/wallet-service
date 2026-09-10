package com.paytm.pml.wallet.service;

import com.paytm.pml.wallet.domain.Wallet;
import com.paytm.pml.wallet.metrics.WalletMetrics;
import com.paytm.pml.wallet.repo.WalletRepository;
import com.paytm.pml.wallet.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository wallets;
    private final WalletMetrics metrics;

    public WalletService(WalletRepository wallets, WalletMetrics metrics) {
        this.wallets = wallets;
        this.metrics = metrics;
    }

    /**
     * Race-free get-or-create. Two concurrent calls for the same user yield one
     * wallet: the unique constraint on owner_user lets exactly one INSERT win;
     * the loser catches the violation and re-reads the winner's row.
     */
    @Transactional
    public Wallet getOrCreate(String ownerUser, long initialBalancePaise) {
        // INSERT ... ON CONFLICT DO NOTHING: exactly one concurrent insert wins,
        // the losers affect 0 rows and DO NOT abort the transaction. Then re-read
        // the authoritative row (ours if we won, the winner's if we lost).
        int inserted = wallets.insertIfAbsent(UUID.randomUUID(), ownerUser, initialBalancePaise);
        if (inserted == 1) {
            metrics.walletCreated();
            MDC.put("event", "wallet_created");
            log.info("wallet created for user={}", ownerUser);
        } else {
            MDC.put("event", "wallet_create_race_resolved");
            log.info("concurrent get-or-create for user={} resolved to existing wallet", ownerUser);
        }
        Wallet wallet = wallets.findByOwnerUser(ownerUser)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "wallet_create_failed", "could not resolve wallet for user " + ownerUser));
        MDC.put("wallet_id", wallet.getId().toString());
        return wallet;
    }

    @Transactional(readOnly = true)
    public Wallet get(UUID id) {
        return wallets.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "wallet_not_found", "no wallet with id " + id));
    }
}
