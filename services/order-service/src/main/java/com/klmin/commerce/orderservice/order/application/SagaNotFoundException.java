package com.klmin.commerce.orderservice.order.application;

import com.klmin.commerce.orderservice.common.error.NotFoundException;

import java.util.UUID;

public class SagaNotFoundException extends NotFoundException {

    public SagaNotFoundException(UUID orderId) {
        super("saga not found: orderId=" + orderId);
    }
}
