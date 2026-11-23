package com.klmin.commerce.orderservice.order.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * 단계 타임아웃 정책 (ADR 0007).
 *
 * @param stepTimeout       한 단계에서 응답을 기다리는 시간. 지나면 같은 커맨드를 다시 보낸다
 * @param maxResends        재고 예약·포인트 차감에서 다시 보내는 최대 횟수. 넘으면 반영됐다고 보고 역순 보상
 * @param maxPaymentResends 결제에서 다시 보내는 최대 횟수. 넘으면 결제 취소(CancelPayment)부터 역순 보상.
 *                          결제는 결과가 애매해(승인됐을 수 있음) 더 오래 기다린다
 */
public record SagaTimeoutPolicy(Duration stepTimeout, int maxResends, int maxPaymentResends) {

    public SagaTimeoutPolicy {
        Objects.requireNonNull(stepTimeout, "stepTimeout");
        if (stepTimeout.isNegative() || stepTimeout.isZero()) {
            throw new IllegalArgumentException("stepTimeout must be positive");
        }
        if (maxResends < 0 || maxPaymentResends < 0) {
            throw new IllegalArgumentException("max resends must not be negative");
        }
    }
}
