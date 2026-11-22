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

record PlaceOrderRequest(
        @NotEmpty @Size(max = Order.MAX_LINES) List<@Valid @NotNull LineRequest> lines) {

    record LineRequest(
            @NotNull @Positive Long productId,
            @NotNull @Positive @Max(OrderLine.MAX_QUANTITY) Integer quantity,
            @NotNull @PositiveOrZero Long unitPrice) {
    }

    PlaceOrderCommand toCommand(long userId) {
        return new PlaceOrderCommand(userId, lines.stream()
                .map(l -> new PlaceOrderCommand.Line(l.productId(), l.quantity(), l.unitPrice()))
                .toList());
    }
}
