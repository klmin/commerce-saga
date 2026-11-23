package com.klmin.commerce.paymentservice.payment.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    Optional<PaymentJpaEntity> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentJpaEntity p where p.orderId = :orderId")
    Optional<PaymentJpaEntity> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

    // 부분 인덱스(idx_payments_pending_reconcile)와 같은 조건을 리터럴로 둔다
    @Query("""
            select p.orderId from PaymentJpaEntity p
            where p.status = com.klmin.commerce.paymentservice.payment.domain.PaymentStatus.UNKNOWN
               or (p.status = com.klmin.commerce.paymentservice.payment.domain.PaymentStatus.APPROVED
                   and p.cancelRequested = true)
            order by p.updatedAt
            """)
    List<UUID> findPendingOrderIds(Limit limit);
}
