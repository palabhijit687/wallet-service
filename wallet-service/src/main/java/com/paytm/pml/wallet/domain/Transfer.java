package com.paytm.pml.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transfers", uniqueConstraints = {
        @UniqueConstraint(name = "transfers_idempotency_unique", columnNames = "idempotency_key")
})
public class Transfer {

    public enum Status {
        PENDING,
        SUCCEEDED,
        DECLINED,
        REVERSED
    }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private String requestedBy;

    @Column(name = "from_wallet", nullable = false, updatable = false)
    private UUID fromWallet;

    @Column(name = "to_wallet", nullable = false, updatable = false)
    private UUID toWallet;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "decline_reason")
    private String declineReason;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "reversal_of", updatable = false)
    private UUID reversalOf;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Transfer() {
    }

    public Transfer(UUID id, String idempotencyKey, String requestedBy, UUID fromWallet, UUID toWallet,
                    long amountPaise, Status status, String declineReason, String requestHash) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.requestedBy = requestedBy;
        this.fromWallet = fromWallet;
        this.toWallet = toWallet;
        this.amountPaise = amountPaise;
        this.status = status;
        this.declineReason = declineReason;
        this.requestHash = requestHash;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public UUID getFromWallet() {
        return fromWallet;
    }

    public UUID getToWallet() {
        return toWallet;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public Status getStatus() {
        return status;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getReversalOf() {
        return reversalOf;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
