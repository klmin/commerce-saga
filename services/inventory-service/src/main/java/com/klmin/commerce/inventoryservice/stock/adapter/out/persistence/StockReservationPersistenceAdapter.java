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
    public void insert(StockReservation reservation) {
        repository.save(new StockReservationJpaEntity(reservation.orderId(), reservation.status(),
                reservation.reason(), reservation.createdAt(),
                reservation.lines().stream()
                        .map(l -> new ReservationLineEmbeddable(l.productId(), l.quantity()))
                        .toList()));
    }

    private static StockReservation toDomain(StockReservationJpaEntity e) {
        return StockReservation.restore(e.getOrderId(), e.getStatus(), e.getReason(),
                e.getLines().stream().map(l -> new ReservationLine(l.getProductId(), l.getQuantity())).toList(),
                e.getCreatedAt());
    }
}
