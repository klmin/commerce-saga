package com.klmin.commerce.pointservice.point.application.port.out;

import java.util.UUID;

/** 오케스트레이터(order-service)에 결과를 응답한다. 구현은 outbox 저장이라 호출한 트랜잭션과 함께 커밋된다 */
public interface SendPointReplyPort {

    void pointUsed(UUID orderId);

    void pointUseFailed(UUID orderId, String reason);

    void pointRefunded(UUID orderId);
}
