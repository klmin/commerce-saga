package com.klmin.commerce.paymentservice.payment.application.port.out;

import com.klmin.commerce.paymentservice.payment.domain.Payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentPort {

    Optional<Payment> findByOrderId(UUID orderId);

    /** 결제 행을 비관적 락(SELECT ... FOR UPDATE)으로 잠가 가져온다. 취소 요청과 PG 조회 결과 반영을 줄 세운다 */
    Optional<Payment> findByOrderIdForUpdate(UUID orderId);

    /** 보정 대상(PG 결과를 모름, 또는 취소 요청을 받았는데 승인 상태)의 주문 id. 오래된 순, 최대 limit 개. 잠그지 않는다 */
    List<UUID> findPendingOrderIds(int limit);

    /** 같은 orderId 가 이미 있으면 예외 (동시에 처리된 중복 커맨드 중 하나만 커밋된다) */
    void insert(Payment payment);

    /** {@link #findByOrderIdForUpdate} 로 같은 트랜잭션에서 잠근 결제를 저장한다 */
    void update(Payment payment);
}
