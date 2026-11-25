package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.port.out.CartPort;
import com.klmin.commerce.orderservice.order.domain.CartItem;
import com.klmin.commerce.orderservice.order.domain.ProductId;
import com.klmin.commerce.orderservice.order.domain.UserId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** cart_items 인메모리 대역 */
class InMemoryCart implements CartPort {

    final Map<Long, Map<Long, CartItem>> store = new LinkedHashMap<>();

    @Override
    public List<CartItem> findByUserId(UserId userId) {
        return new ArrayList<>(store.getOrDefault(userId.value(), Map.of()).values());
    }

    @Override
    public void upsert(UserId userId, CartItem item, Instant now) {
        Map<Long, CartItem> items = store.computeIfAbsent(userId.value(), k -> new LinkedHashMap<>());
        CartItem existing = items.get(item.productId().value());
        items.put(item.productId().value(), existing == null ? item
                : new CartItem(item.productId(), item.quantity(), existing.addedPrice(), existing.addedAt()));
    }

    @Override
    public void delete(UserId userId, ProductId productId) {
        store.getOrDefault(userId.value(), new LinkedHashMap<>()).remove(productId.value());
    }

    @Override
    public void deleteAll(UserId userId, Collection<ProductId> productIds) {
        productIds.forEach(id -> delete(userId, id));
    }
}
