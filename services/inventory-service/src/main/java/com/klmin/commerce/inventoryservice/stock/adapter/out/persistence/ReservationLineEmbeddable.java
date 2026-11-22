package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
class ReservationLineEmbeddable {

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(nullable = false)
    private int quantity;

    protected ReservationLineEmbeddable() {
    }

    ReservationLineEmbeddable(long productId, int quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    long getProductId() {
        return productId;
    }

    int getQuantity() {
        return quantity;
    }
}
