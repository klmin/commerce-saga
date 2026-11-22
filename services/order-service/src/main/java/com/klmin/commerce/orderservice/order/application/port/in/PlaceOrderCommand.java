package com.klmin.commerce.orderservice.order.application.port.in;

import java.util.List;

/** @param pointAmount 사용할 포인트 (0 이상 합계 이하). 나머지를 결제한다 */
public record PlaceOrderCommand(long userId, List<Line> lines, long pointAmount) {

    public PlaceOrderCommand {
        lines = List.copyOf(lines);
    }

    public record Line(long productId, int quantity, long unitPrice) {
    }
}
