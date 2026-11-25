package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 사용자 장바구니 (사용자당 하나, 줄은 상품마다 하나). 최대 줄 수는 주문 최대 줄 수와 같다.
 * 주문 전 단계라 주문 컨텍스트(order-service)에 둔다: 장바구니 주문은 주문 생성과 줄 삭제를 한 트랜잭션으로 한다 (ADR 0016)
 */
public final class Cart {

    public static final int MAX_ITEMS = Order.MAX_LINES;

    private final UserId userId;
    private final Map<ProductId, CartItem> items = new LinkedHashMap<>();

    private Cart(UserId userId, List<CartItem> items) {
        this.userId = Objects.requireNonNull(userId, "userId");
        items.forEach(i -> this.items.put(i.productId(), i));
    }

    public static Cart of(UserId userId, List<CartItem> items) {
        return new Cart(userId, items);
    }

    /**
     * 담기·수량 바꾸기. 이미 있는 줄은 수량만 바꾸고 담을 때 가격은 그대로 둔다
     *
     * @param currentPrice 지금 가격 (새 줄의 "담을 때 가격"이 된다)
     */
    public CartItem put(ProductId productId, Quantity quantity, Money currentPrice, Instant now) {
        if (quantity.value() > OrderLine.MAX_QUANTITY) {
            throw new DomainValidationException("quantity must be between 1 and " + OrderLine.MAX_QUANTITY);
        }
        CartItem existing = items.get(productId);
        CartItem item;
        if (existing != null) {
            item = new CartItem(productId, quantity, existing.addedPrice(), existing.addedAt());
        } else {
            if (items.size() >= MAX_ITEMS) {
                throw new DomainValidationException("cart can have at most " + MAX_ITEMS + " items");
            }
            item = new CartItem(productId, quantity, currentPrice, now);
        }
        items.put(productId, item);
        return item;
    }

    public UserId userId() {
        return userId;
    }

    public List<CartItem> items() {
        return List.copyOf(items.values());
    }
}
