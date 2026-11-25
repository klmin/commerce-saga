package com.klmin.commerce.paymentservice.payment.application.port.out;

import java.util.UUID;

/**
 * PG 승인 요청을 보낸 적이 있는 주문 (PROGRESS 4-4절 ③). PG 를 부르기 전에 남기고 바로 커밋한다.
 * 서킷 브레이커가 이번 호출을 막았을 때 이전 시도(재전달·Saga 재전송 전의 시도)가 PG 에 닿았을 수 있는지 판단하는 데 쓴다
 */
public interface PgApproveAttemptPort {

    /**
     * 시도를 남긴다.
     *
     * @return 이 주문에 이전 시도 기록이 이미 있었으면 true
     */
    boolean recordAttempt(UUID orderId);
}
