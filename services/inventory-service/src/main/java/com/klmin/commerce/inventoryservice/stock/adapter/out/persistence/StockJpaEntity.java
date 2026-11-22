package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "stocks")
class StockJpaEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private int reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockJpaEntity() {
    }

    void change(int available, int reserved, Instant updatedAt) {
        this.available = available;
        this.reserved = reserved;
        this.updatedAt = updatedAt;
    }

    Long getProductId() {
        return productId;
    }

    int getAvailable() {
        return available;
    }

    int getReserved() {
        return reserved;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
