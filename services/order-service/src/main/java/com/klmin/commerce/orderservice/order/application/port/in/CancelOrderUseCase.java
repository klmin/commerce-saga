package com.klmin.commerce.orderservice.order.application.port.in;

public interface CancelOrderUseCase {

    /**
     * 주문 취소 요청 (ADR 0020). 진행 중이면 다음 응답에서 보상으로 돌리고, 완료됐으면 결제 취소(환불)부터 되돌린다.
     * 여러 번 보내도 한 번만 반영된다. 남의 주문이거나 없는 주문이면 {@code OrderNotFoundException}(404)
     */
    CancelOrderResult cancelOrder(CancelOrderCommand command);
}
