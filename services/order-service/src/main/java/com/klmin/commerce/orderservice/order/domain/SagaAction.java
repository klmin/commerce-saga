package com.klmin.commerce.orderservice.order.domain;

/**
 * Saga 전이 결과로 해야 할 일. 도메인은 무엇을 할지만 정하고, 메시지 발행·주문 상태 반영은 애플리케이션이 한다.
 */
public sealed interface SagaAction {

    record ReserveStock() implements SagaAction {
    }

    record UsePoint(Points amount) implements SagaAction {
    }

    record Pay(Money amount) implements SagaAction {
    }

    record RefundPoint() implements SagaAction {
    }

    record ReleaseStock() implements SagaAction {
    }

    /** 결제 취소 (결제 결과를 끝내 못 받아 포기). 승인됐으면 결제 서비스가 환불한다 */
    record CancelPayment(Money amount) implements SagaAction {
    }

    /** 주문 완료 처리 + OrderCompleted 이벤트 */
    record Complete() implements SagaAction {
    }

    /** 주문 취소 처리 + OrderCancelled 이벤트. reason 은 처음 실패한 단계의 사유 */
    record Cancel(String reason) implements SagaAction {
    }

    /** 현재 단계와 맞지 않는 응답 (중복·늦은 응답). 아무것도 하지 않는다 */
    record Ignore() implements SagaAction {
    }
}
