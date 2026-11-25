package com.klmin.commerce.orderservice.order.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 장바구니 한 줄. addedPrice = 담을 때 본 가격 (그 뒤 가격이 바뀌었는지 보여 주려고 남긴다, ADR 0016).
 * 주문 금액은 이 값이 아니라 주문 때 다시 확인한 가격이다
 */
public record CartItem(ProductId productId, Quantity quantity, Money addedPrice, Instant addedAt) {

    public CartItem {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(addedPrice, "addedPrice");
        Objects.requireNonNull(addedAt, "addedAt");
    }
}
