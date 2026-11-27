package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.UUID;

@Embeddable
class ReservationLineEmbeddable {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    protected ReservationLineEmbeddable() {
    }

    ReservationLineEmbeddable(UUID productId, int quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    UUID getProductId() {
        return productId;
    }

    int getQuantity() {
        return quantity;
    }
}
