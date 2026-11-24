package com.klmin.commerce.orderservice.order.application.port.in;

/** 멱등 기록 정리 (스케줄러가 부른다) */
public interface CleanupIdempotencyRecordsUseCase {

    /** @return 이번에 지운 행 수 */
    int cleanup();
}
