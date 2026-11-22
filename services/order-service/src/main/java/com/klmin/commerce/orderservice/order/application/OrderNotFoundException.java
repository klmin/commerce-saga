package com.klmin.commerce.orderservice.order.application;

import com.klmin.commerce.orderservice.common.error.NotFoundException;

import java.util.UUID;

public class OrderNotFoundException extends NotFoundException {

    public OrderNotFoundException(UUID orderId) {
        super("order not found: " + orderId);
    }
}
