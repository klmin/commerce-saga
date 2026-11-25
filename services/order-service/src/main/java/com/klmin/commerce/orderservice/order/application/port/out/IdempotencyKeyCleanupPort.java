package com.klmin.commerce.orderservice.order.application.port.out;

import java.time.Instant;

/** 보존 기간이 지난 주문 Idempotency-Key 정리 (시나리오 10, PROGRESS 4-4절 ②). 주문은 지우지 않는다 */
public interface IdempotencyKeyCleanupPort {

    /** created_at 이 cutoff 보다 이른 키를 최대 limit 개 지운다. @return 지운 행 수 */
    int deleteCreatedBefore(Instant cutoff, int limit);
}
