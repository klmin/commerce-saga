package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.UUID;

@Embeddable
class OrderLineEmbeddable {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    protected OrderLineEmbeddable() {
    }

    OrderLineEmbeddable(UUID productId, int quantity, long unitPrice) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    UUID getProductId() {
        return productId;
    }

    int getQuantity() {
        return quantity;
    }

    long getUnitPrice() {
        return unitPrice;
    }
}
