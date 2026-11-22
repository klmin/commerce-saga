package com.klmin.commerce.orderservice.order.application.port.out;

import com.klmin.commerce.orderservice.order.domain.Order;

import java.util.Optional;
import java.util.UUID;

public interface LoadOrderPort {

    Optional<Order> findById(UUID orderId);
}
