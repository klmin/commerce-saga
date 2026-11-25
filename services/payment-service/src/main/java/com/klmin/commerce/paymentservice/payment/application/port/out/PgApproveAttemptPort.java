package com.klmin.commerce.paymentservice.payment.application.port.out;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * PG 승인 호출 예약 (PROGRESS 4-4절 ③). 결제 처리는 PG 를 부르기 전에 반드시 {@link #claim} 하고, 각 호출은 바로 커밋된다.
 * 서킷 브레이커가 이번 호출을 막았을 때, 이 주문으로 PG 를 부른(부를) 다른 처리가 없다고 확정할 수 있어야만 거절로 확정한다
 */
public interface PgApproveAttemptPort {

    /**
     * 이 주문의 PG 승인 호출을 하나 예약한다.
     *
     * @return 이 주문의 몇 번째 예약인지(1부터). 봉인된 주문이면 비어 있다 → PG 를 부르면 안 된다
     *         (봉인 = 막힌 첫 처리 하나뿐이었다 = 이 주문은 PG 에 한 번도 가지 않았고 앞으로도 가지 않는다)
     */
    OptionalInt claim(UUID orderId);

    /**
     * 예약이 하나(이 처리)뿐이면 봉인한다. 이후 {@link #claim} 은 모두 비어 있다.
     *
     * @return 봉인했으면 true. 다른 처리가 예약했으면 false (그 처리가 PG 를 불렀을 수 있다)
     */
    boolean sealIfOnlyClaim(UUID orderId);
}
