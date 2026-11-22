package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

/**
 * 주문 항목 (Order 애그리거트 내부의 값 객체).
 * 단가는 1단계에서 요청 값으로 받는다. 상품 서비스(5단계)가 생기면 그 가격으로 바꾼다.
 * <p>
 * 단가·수량·항목 수 상한이 있어 주문 합계가 long 범위를 넘지 않는다 (최대 50 x 1,000 x 1억).
 */
public record OrderLine(long productId, int quantity, long unitPrice) {

    public static final int MAX_QUANTITY = 1_000;
    public static final long MAX_UNIT_PRICE = 100_000_000L;

    public OrderLine {
        if (productId <= 0) {
            throw new DomainValidationException("productId must be positive");
        }
        if (quantity <= 0 || quantity > MAX_QUANTITY) {
            throw new DomainValidationException("quantity must be between 1 and " + MAX_QUANTITY);
        }
        if (unitPrice < 0 || unitPrice > MAX_UNIT_PRICE) {
            throw new DomainValidationException("unitPrice must be between 0 and " + MAX_UNIT_PRICE);
        }
    }

    public long amount() {
        return Math.multiplyExact(unitPrice, (long) quantity);
    }
}
