package com.paytm.pml.wallet.web;

import com.paytm.pml.wallet.service.TransferFacade;
import com.paytm.pml.wallet.service.TransferService;
import com.paytm.pml.wallet.web.dto.Dtos.CreateTransferRequest;
import com.paytm.pml.wallet.web.dto.Dtos.ReverseTransferRequest;
import com.paytm.pml.wallet.web.dto.Dtos.TransferResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferFacade transfers;

    public TransferController(TransferFacade transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody CreateTransferRequest body,
                                                   HttpServletRequest request) {
        String user = (String) request.getAttribute(AuthFilter.CALLER_ATTR);
        TransferService.TransferResult result = transfers.transfer(
                user, body.from(), body.to(), body.amountPaise(), body.idempotencyKey());
        return ResponseEntity.ok(TransferResponse.of(result.transfer(), result.replay()));
    }

    /** Reverse/refund a prior successful transfer, with its own idempotency key. */
    @PostMapping("/{id}/reverse")
    public ResponseEntity<TransferResponse> reverse(@PathVariable UUID id,
                                                    @Valid @RequestBody ReverseTransferRequest body,
                                                    HttpServletRequest request) {
        String user = (String) request.getAttribute(AuthFilter.CALLER_ATTR);
        TransferService.TransferResult result = transfers.reverse(id, user, body.idempotencyKey());
        return ResponseEntity.ok(TransferResponse.of(result.transfer(), result.replay()));
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.of(transfers.get(id), false);
    }
}
