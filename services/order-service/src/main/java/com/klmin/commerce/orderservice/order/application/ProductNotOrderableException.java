package com.klmin.commerce.orderservice.order.application;

import com.klmin.commerce.orderservice.common.error.UnprocessableRequestException;

import java.util.List;

/** 없는 상품이거나 판매 중지 상품 (422) */
public class ProductNotOrderableException extends UnprocessableRequestException {

    public ProductNotOrderableException(List<Long> productIds) {
        super("주문할 수 없는 상품입니다: " + productIds);
    }
}
