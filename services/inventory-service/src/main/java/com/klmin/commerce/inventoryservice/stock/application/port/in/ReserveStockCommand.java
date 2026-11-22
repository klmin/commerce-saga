package com.klmin.commerce.inventoryservice.stock.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ReserveStockCommand(UUID orderId, List<Line> lines) {

    public ReserveStockCommand {
        Objects.requireNonNull(orderId, "orderId");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public record Line(long productId, int quantity) {
    }
}
