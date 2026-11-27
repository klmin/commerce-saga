package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * @param pointAmount 사용할 포인트 (생략하면 0). 합계보다 크면 400
 * @param fromCart    장바구니에서 주문하면 true: 주문한 상품을 장바구니에서 뺀다 (생략하면 false)
 */
record PlaceOrderRequest(
        @NotEmpty @Size(max = Order.MAX_LINES) List<@Valid @NotNull LineRequest> lines,
        @PositiveOrZero Long pointAmount,
        Boolean fromCart) {

    record LineRequest(
            @NotNull UUID productId,
            @NotNull @Positive @Max(OrderLine.MAX_QUANTITY) Integer quantity,
            @NotNull @PositiveOrZero @Max(OrderLine.MAX_UNIT_PRICE) Long unitPrice) {
    }

    PlaceOrderCommand toCommand(long userId, String idempotencyKey) {
        return new PlaceOrderCommand(userId, lines.stream()
                .map(l -> new PlaceOrderCommand.Line(l.productId(), l.quantity(), l.unitPrice()))
                .toList(),
                pointAmount == null ? 0L : pointAmount, idempotencyKey, Boolean.TRUE.equals(fromCart));
    }
}
