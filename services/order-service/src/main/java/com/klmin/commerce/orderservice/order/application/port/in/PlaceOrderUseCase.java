package com.klmin.commerce.orderservice.order.application.port.in;

public interface PlaceOrderUseCase {

    /**
     * 주문(PENDING)과 Saga 를 저장하고, 같은 트랜잭션에서 첫 커맨드(재고 예약)를 outbox 에 남긴다.
     * <p>
     * Idempotency-Key 가 있으면 같은 사용자·같은 키의 주문은 하나만 만든다. 이미 있으면 내용이 같을 때 처음 주문을
     * 그대로 돌려주고(created = false, 커맨드도 다시 보내지 않음), 다르면 IdempotencyKeyReusedException.
     */
    PlaceOrderResult placeOrder(PlaceOrderCommand command);
}
