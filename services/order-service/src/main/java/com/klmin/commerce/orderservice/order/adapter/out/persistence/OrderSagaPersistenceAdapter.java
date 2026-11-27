package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.application.port.out.OrderSagaPort;
import com.klmin.commerce.orderservice.order.domain.Money;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import com.klmin.commerce.orderservice.order.domain.Points;
import com.klmin.commerce.orderservice.order.domain.SagaHistoryEntry;
import com.klmin.commerce.orderservice.order.domain.UserId;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class OrderSagaPersistenceAdapter implements OrderSagaPort {

    private final OrderSagaJpaRepository repository;

    OrderSagaPersistenceAdapter(OrderSagaJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<OrderSaga> findByOrderId(UUID orderId) {
        return repository.findById(orderId).map(OrderSagaPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<OrderSaga> findByOrderIdForUpdate(UUID orderId) {
        return repository.findByIdForUpdate(orderId).map(OrderSagaPersistenceAdapter::toDomain);
    }

    @Override
    public List<UUID> findStuckOrderIds(Instant updatedBefore, int limit) {
        return repository.findStuckOrderIds(updatedBefore, Limit.of(limit));
    }

    @Override
    public void insert(OrderSaga saga) {
        repository.save(new OrderSagaJpaEntity(saga.orderId(), saga.userId().value(), saga.pointAmount().value(),
                saga.paidAmount().value(), saga.state(), saga.pointUsed(), saga.failureReason(), saga.stepResends(),
                saga.createdAt(), saga.updatedAt(), toEmbeddables(saga.history())));
    }

    // 같은 트랜잭션에서 잠가 조회한 영속 엔티티에 값만 옮긴다. 커밋 때 더티 체킹으로 UPDATE
    @Override
    public void update(OrderSaga saga) {
        OrderSagaJpaEntity entity = repository.findById(saga.orderId())
                .orElseThrow(() -> new IllegalStateException("saga not found: " + saga.orderId()));
        entity.change(saga.state(), saga.pointUsed(), saga.failureReason(), saga.stepResends(),
                saga.cancelRequested(), saga.afterCompletion(), saga.updatedAt(),
                toEmbeddables(saga.history()));
    }

    private static List<SagaHistoryEmbeddable> toEmbeddables(List<SagaHistoryEntry> history) {
        return history.stream()
                .map(h -> new SagaHistoryEmbeddable(h.event(), h.state(), h.reason(), h.occurredAt()))
                .toList();
    }

    private static OrderSaga toDomain(OrderSagaJpaEntity e) {
        return OrderSaga.restore(e.getOrderId(), UserId.of(e.getUserId()), Points.of(e.getPointAmount()),
                Money.of(e.getPaidAmount()),
                e.getState(), e.isPointUsed(), e.getFailureReason(), e.getStepResends(), e.isCancelRequested(),
                e.isAfterCompletion(),
                e.getHistory().stream()
                        .map(h -> new SagaHistoryEntry(h.getEvent(), h.getState(), h.getReason(), h.getOccurredAt()))
                        .toList(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
