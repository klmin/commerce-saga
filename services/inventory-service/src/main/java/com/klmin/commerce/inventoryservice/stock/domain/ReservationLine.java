package com.klmin.commerce.inventoryservice.stock.domain;

import java.util.Objects;
import java.util.UUID;

public record ReservationLine(ProductId productId, Quantity quantity) {

    public ReservationLine {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantity, "quantity");
    }

    public static ReservationLine of(UUID productId, int quantity) {
        return new ReservationLine(ProductId.of(productId), Quantity.of(quantity));
    }
}
