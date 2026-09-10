package com.paytm.pml.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "wallets", uniqueConstraints = {
        @UniqueConstraint(name = "wallets_owner_unique", columnNames = "owner_user")
})
public class Wallet {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "owner_user", nullable = false, updatable = false)
    private String ownerUser;

    @Column(name = "balance_paise", nullable = false)
    private long balancePaise;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Wallet() {
    }

    public Wallet(UUID id, String ownerUser, long balancePaise) {
        this.id = id;
        this.ownerUser = ownerUser;
        this.balancePaise = balancePaise;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerUser() {
        return ownerUser;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
