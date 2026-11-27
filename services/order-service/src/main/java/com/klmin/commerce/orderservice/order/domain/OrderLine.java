package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

import java.util.Objects;
import java.util.UUID;

/**
 * 주문 항목 (Order 애그리거트 내부의 값 객체).
 * 단가는 1단계에서 요청 값으로 받는다. 상품 서비스(5단계)가 생기면 그 가격으로 바꾼다.
 * <p>
 * 단가·수량·항목 수 상한이 있어 주문 합계가 long 범위를 넘지 않는다 (최대 50 x 1,000 x 1억).
 */
public record OrderLine(ProductId productId, Quantity quantity, Money unitPrice) {

    public static final int MAX_QUANTITY = 1_000;
    public static final long MAX_UNIT_PRICE = 100_000_000L;

    public OrderLine {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity.value() > MAX_QUANTITY) {
            throw new DomainValidationException("quantity must be between 1 and " + MAX_QUANTITY);
        }
        if (unitPrice.value() > MAX_UNIT_PRICE) {
            throw new DomainValidationException("unitPrice must be between 0 and " + MAX_UNIT_PRICE);
        }
    }

    /** 원시 값에서 만든다 (요청·저장소 매핑용). 각 값의 규칙 위반은 DomainValidationException */
    public static OrderLine of(UUID productId, int quantity, long unitPrice) {
        return new OrderLine(ProductId.of(productId), Quantity.of(quantity), Money.of(unitPrice));
    }

    public Money amount() {
        return unitPrice.times(quantity);
    }
}
