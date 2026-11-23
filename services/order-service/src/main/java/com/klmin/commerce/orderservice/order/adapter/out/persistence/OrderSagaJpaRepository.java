package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface OrderSagaJpaRepository extends JpaRepository<OrderSagaJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OrderSagaJpaEntity s where s.orderId = :orderId")
    Optional<OrderSagaJpaEntity> findByIdForUpdate(@Param("orderId") UUID orderId);

    // 진행 중 Saga 의 부분 인덱스(idx_order_sagas_in_progress_updated_at)를 타도록 조건을 리터럴로 둔다
    @Query("""
            select s.orderId from OrderSagaJpaEntity s
            where s.state not in (com.klmin.commerce.orderservice.order.domain.SagaState.COMPLETED,
                                  com.klmin.commerce.orderservice.order.domain.SagaState.CANCELLED)
              and s.updatedAt <= :updatedBefore
            order by s.updatedAt
            """)
    List<UUID> findStuckOrderIds(@Param("updatedBefore") Instant updatedBefore, Limit limit);
}
