package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import com.klmin.commerce.inventoryservice.stock.domain.ReservationStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "stock_reservations")
class StockReservationJpaEntity {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // null 이면 새 엔티티로 보고 persist 한다 (같은 orderId 가 이미 있으면 PK 위반으로 실패)
    @Version
    private Long version;

    @ElementCollection
    @CollectionTable(name = "stock_reservation_lines", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<ReservationLineEmbeddable> lines = new ArrayList<>();

    protected StockReservationJpaEntity() {
    }

    StockReservationJpaEntity(UUID orderId, ReservationStatus status, String reason, Instant createdAt,
                              List<ReservationLineEmbeddable> lines) {
        this.orderId = orderId;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
        this.lines = new ArrayList<>(lines);
    }

    UUID getOrderId() {
        return orderId;
    }

    ReservationStatus getStatus() {
        return status;
    }

    String getReason() {
        return reason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    List<ReservationLineEmbeddable> getLines() {
        return lines;
    }
}
