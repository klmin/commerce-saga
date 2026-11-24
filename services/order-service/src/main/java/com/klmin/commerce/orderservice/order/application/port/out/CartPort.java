package com.klmin.commerce.orderservice.order.application.port.out;

import com.klmin.commerce.orderservice.order.domain.CartItem;
import com.klmin.commerce.orderservice.order.domain.ProductId;
import com.klmin.commerce.orderservice.order.domain.UserId;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface CartPort {

    /** 담은 순서 */
    List<CartItem> findByUserId(UserId userId);

    /** 없으면 넣고 있으면 수량만 바꾼다 (담을 때 가격·시각은 그대로) */
    void upsert(UserId userId, CartItem item, Instant now);

    void delete(UserId userId, ProductId productId);

    /** 장바구니 주문: 주문 트랜잭션에 참여해 주문한 상품의 줄을 지운다 */
    void deleteAll(UserId userId, Collection<ProductId> productIds);
}
