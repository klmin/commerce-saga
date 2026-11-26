package com.klmin.commerce.orderservice.order.application.port.out;

import com.klmin.commerce.orderservice.order.domain.SagaState;

import java.time.Duration;

/**
 * 끝난 Saga 의 결과 기록 (메트릭). 진행 중 Saga 수는 DB 로 세지만, 정상 주문은 수집 간격보다 빨리 끝나서
 * 완료·취소 건수와 걸린 시간은 끝나는 순간에 남긴다.
 * 구현은 호출한 트랜잭션이 커밋된 뒤에만 반영해야 한다 (롤백된 전이는 세지 않는다).
 */
public interface SagaOutcomePort {

    /**
     * @param result  {@code COMPLETED} 또는 {@code CANCELLED}
     * @param elapsed Saga 시작부터 끝날 때까지
     */
    void sagaFinished(SagaState result, Duration elapsed);
}
