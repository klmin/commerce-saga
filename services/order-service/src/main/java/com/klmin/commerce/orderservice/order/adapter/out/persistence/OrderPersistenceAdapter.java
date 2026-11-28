package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderPort;
import com.klmin.commerce.orderservice.order.application.port.out.SaveOrderPort;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderLine;
import com.klmin.commerce.orderservice.order.domain.Points;
import com.klmin.commerce.orderservice.order.domain.UserId;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class OrderPersistenceAdapter implements LoadOrderPort, SaveOrderPort {

    private final OrderJpaRepository repository;

    OrderPersistenceAdapter(OrderJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void insert(Order order) {
        repository.save(toEntity(order));
    }

    // 영속 상태 엔티티에 값만 옮긴다. 커밋 때 더티 체킹으로 UPDATE (같은 트랜잭션에서 조회했으면 1차 캐시에서 나온다)
    @Override
    public void update(Order order) {
        OrderJpaEntity entity = repository.findById(order.id())
                .orElseThrow(() -> new IllegalStateException("order not found: " + order.id()));
        entity.changeStatus(order.status(), order.cancelReason(), order.cancelRequestedAt(), order.updatedAt());
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return repository.findById(orderId).map(OrderPersistenceAdapter::toDomain);
    }

    private static OrderJpaEntity toEntity(Order order) {
        return new OrderJpaEntity(order.id(), order.userId().value(), order.status(), order.totalAmount().value(),
                order.pointAmount().value(), order.cancelReason(), order.createdAt(), order.updatedAt(),
                order.lines().stream()
                        .map(l -> new OrderLineEmbeddable(l.productId().value(), l.quantity().value(), l.unitPrice().value()))
                        .toList());
    }

    private static Order toDomain(OrderJpaEntity entity) {
        return Order.restore(entity.getId(), UserId.of(entity.getUserId()),
                entity.getLines().stream()
                        .map(l -> OrderLine.of(l.getProductId(), l.getQuantity(), l.getUnitPrice()))
                        .toList(),
                Points.of(entity.getPointAmount()), entity.getStatus(), entity.getCancelReason(),
                entity.getCancelRequestedAt(), entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
