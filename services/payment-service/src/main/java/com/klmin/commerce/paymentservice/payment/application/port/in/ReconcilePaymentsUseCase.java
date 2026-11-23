package com.klmin.commerce.paymentservice.payment.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * 결제 보정 (ADR 0007). PG 결과를 모르는 결제(UNKNOWN)는 PG 조회로 확정하고,
 * 취소 요청을 받았는데 승인 상태인 결제는 PG 환불한다 (늦은 승인 자동 환불)
 */
public interface ReconcilePaymentsUseCase {

    /** 보정 대상 결제의 주문 id (오래된 순, 한 번에 batch-size 개) */
    List<UUID> findPending();

    /** 결제 하나를 보정한다. PG 호출은 트랜잭션 밖, 결과 반영은 결제 행을 잠근 트랜잭션에서 */
    void reconcile(UUID orderId);
}
