package com.klmin.commerce.productservice.product.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 가격 변경 이력. INSERT 만 한다 */
@Entity
@Table(name = "product_price_history")
class PriceHistoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "old_price", nullable = false, updatable = false)
    private long oldPrice;

    @Column(name = "new_price", nullable = false, updatable = false)
    private long newPrice;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected PriceHistoryJpaEntity() {
    }

    PriceHistoryJpaEntity(UUID productId, long oldPrice, long newPrice, UUID changedBy, Instant changedAt) {
        this.productId = productId;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    long getOldPrice() {
        return oldPrice;
    }

    long getNewPrice() {
        return newPrice;
    }

    UUID getChangedBy() {
        return changedBy;
    }

    Instant getChangedAt() {
        return changedAt;
    }
}
