package com.klmin.commerce.orderservice.order.application.port.in;

import java.util.List;

public record PlaceOrderCommand(long userId, List<Line> lines) {

    public PlaceOrderCommand {
        lines = List.copyOf(lines);
    }

    public record Line(long productId, int quantity, long unitPrice) {
    }
}
