package com.klmin.commerce.orderservice;

import java.util.UUID;

/**
 * 테스트용 고정 상품 id (ADR 0018). {@code pid(n)} = {@code 01900000-0000-7000-8000-00000000000n} 형태라
 * 1~5 는 product-service 시드 상품 id 와 같다.
 */
public final class TestProductIds {

    private TestProductIds() {
    }

    public static UUID pid(long n) {
        return UUID.fromString(String.format("01900000-0000-7000-8000-%012d", n));
    }
}
