package com.klmin.commerce.pointservice.point.application.port.in;

import java.util.UUID;

public interface RefundPointUseCase {

    /**
     * 보상: 이 주문으로 차감한 포인트를 돌려주고 PointRefunded 를 응답한다 (같은 트랜잭션에서 outbox 저장).
     * 여러 번 와도, 차감한 적 없는 주문이어도 한 번만 돌려주고 항상 응답한다.
     */
    void refund(UUID orderId);
}
