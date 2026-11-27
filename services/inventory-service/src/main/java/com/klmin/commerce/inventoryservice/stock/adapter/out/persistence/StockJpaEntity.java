package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stocks")
class StockJpaEntity {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(nullable = false)
    private long available;

    @Column(nullable = false)
    private long reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockJpaEntity() {
    }

    void change(long available, long reserved, Instant updatedAt) {
        this.available = available;
        this.reserved = reserved;
        this.updatedAt = updatedAt;
    }

    UUID getProductId() {
        return productId;
    }

    long getAvailable() {
        return available;
    }

    long getReserved() {
        return reserved;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
