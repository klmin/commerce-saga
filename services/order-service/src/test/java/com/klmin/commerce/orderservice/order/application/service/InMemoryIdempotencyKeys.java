package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.port.out.OrderIdempotencyPort;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** order_idempotency_keys 인메모리 대역 */
class InMemoryIdempotencyKeys implements OrderIdempotencyPort {

    final Map<String, KeyRecord> store = new HashMap<>();

    @Override
    public Optional<KeyRecord> claim(long userId, String key, String requestHash, UUID orderId, Instant now) {
        KeyRecord existing = store.putIfAbsent(userId + "/" + key, new KeyRecord(orderId, requestHash));
        return Optional.ofNullable(existing);
    }

    @Override
    public Optional<KeyRecord> find(long userId, String key) {
        return Optional.ofNullable(store.get(userId + "/" + key));
    }
}
