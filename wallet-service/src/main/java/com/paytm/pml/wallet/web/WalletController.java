package com.paytm.pml.wallet.web;

import com.paytm.pml.wallet.domain.Wallet;
import com.paytm.pml.wallet.service.WalletService;
import com.paytm.pml.wallet.web.dto.Dtos.CreateWalletRequest;
import com.paytm.pml.wallet.web.dto.Dtos.WalletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    /** Get-or-create the caller's wallet. Idempotent per user. */
    @PostMapping
    public ResponseEntity<WalletResponse> create(@RequestBody(required = false) CreateWalletRequest body,
                                                 HttpServletRequest request) {
        String user = (String) request.getAttribute(AuthFilter.CALLER_ATTR);
        long initial = body != null && body.initialBalancePaise() != null ? body.initialBalancePaise() : 0L;
        if (initial < 0) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_balance", "initial_balance_paise cannot be negative");
        }
        Wallet w = wallets.getOrCreate(user, initial);
        return ResponseEntity.ok(WalletResponse.of(w));
    }

    @GetMapping("/{id}")
    public WalletResponse get(@PathVariable UUID id) {
        return WalletResponse.of(wallets.get(id));
    }
}
