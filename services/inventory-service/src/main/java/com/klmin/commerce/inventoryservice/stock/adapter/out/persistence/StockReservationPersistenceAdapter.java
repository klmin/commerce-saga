package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import com.klmin.commerce.inventoryservice.stock.application.port.out.StockReservationPort;
import com.klmin.commerce.inventoryservice.stock.domain.ReservationLine;
import com.klmin.commerce.inventoryservice.stock.domain.StockReservation;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class StockReservationPersistenceAdapter implements StockReservationPort {

    private final StockReservationJpaRepository repository;

    StockReservationPersistenceAdapter(StockReservationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StockReservation> findByOrderId(UUID orderId) {
        return repository.findById(orderId).map(StockReservationPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<StockReservation> findByOrderIdForUpdate(UUID orderId) {
        return repository.findByIdForUpdate(orderId).map(StockReservationPersistenceAdapter::toDomain);
    }

    @Override
    public void insert(StockReservation reservation) {
        repository.save(new StockReservationJpaEntity(reservation.orderId(), reservation.status(),
                reservation.reason(), reservation.createdAt(), reservation.updatedAt(),
                reservation.lines().stream()
                        .map(l -> new ReservationLineEmbeddable(l.productId().value(), l.quantity().value()))
                        .toList()));
    }

    // 같은 트랜잭션에서 조회한 영속 엔티티에 값만 옮긴다. 커밋 때 더티 체킹으로 UPDATE (@Version 으로 동시 변경 감지)
    @Override
    public void update(StockReservation reservation) {
        StockReservationJpaEntity entity = repository.findById(reservation.orderId())
                .orElseThrow(() -> new IllegalStateException("reservation not found: " + reservation.orderId()));
        entity.changeStatus(reservation.status(), reservation.updatedAt());
    }

    private static StockReservation toDomain(StockReservationJpaEntity e) {
        return StockReservation.restore(e.getOrderId(), e.getStatus(), e.getReason(),
                e.getLines().stream()
                        // 값 객체 도입 전에는 잘못된 커맨드(수량 0 등)를 거절할 때 그 줄도 저장했다. 그런 기록은 REJECTED 라
                        // 줄을 쓰지 않으므로 건너뛴다. 복원이 실패하면 같은 주문의 보상(ReleaseStock)이 계속 재시도된다
                        .filter(l -> l.getProductId() > 0 && l.getQuantity() > 0)
                        .map(l -> ReservationLine.of(l.getProductId(), l.getQuantity()))
                        .toList(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
