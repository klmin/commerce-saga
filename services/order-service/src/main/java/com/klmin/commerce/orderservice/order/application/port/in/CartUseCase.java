package com.klmin.commerce.orderservice.order.application.port.in;

/** 내 장바구니 (JWT sub 의 것만. 장바구니 id 를 받지 않는다) */
public interface CartUseCase {

    /** 지금 가격과 함께. product-service 가 응답하지 않으면 담을 때 가격만 (pricesAvailable=false) */
    CartResult get(long userId);

    /** 담기·수량 바꾸기. 없는·판매 중지 상품은 422, product 장애 503 */
    CartResult put(long userId, long productId, int quantity);

    CartResult remove(long userId, long productId);
}
