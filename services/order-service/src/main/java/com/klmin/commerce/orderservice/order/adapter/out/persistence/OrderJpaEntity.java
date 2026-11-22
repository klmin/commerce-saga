package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.domain.OrderStatus;
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
@Table(name = "orders")
class OrderJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "point_amount", nullable = false)
    private long pointAmount;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // 동시에 같은 주문 상태를 바꾸면(중복 응답 동시 처리 등) 한쪽이 실패하고 메시지가 재처리된다.
    // null 이면 새 엔티티로 보고 persist 한다
    @Version
    private Long version;

    @ElementCollection
    @CollectionTable(name = "order_lines", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<OrderLineEmbeddable> lines = new ArrayList<>();

    protected OrderJpaEntity() {
    }

    OrderJpaEntity(UUID id, long userId, OrderStatus status, long totalAmount, long pointAmount,
                   String cancelReason, Instant createdAt, Instant updatedAt, List<OrderLineEmbeddable> lines) {
        this.id = id;
        this.userId = userId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.pointAmount = pointAmount;
        this.cancelReason = cancelReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lines = new ArrayList<>(lines);
    }

    void changeStatus(OrderStatus status, String cancelReason, Instant updatedAt) {
        this.status = status;
        this.cancelReason = cancelReason;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    long getUserId() {
        return userId;
    }

    OrderStatus getStatus() {
        return status;
    }

    long getTotalAmount() {
        return totalAmount;
    }

    long getPointAmount() {
        return pointAmount;
    }

    String getCancelReason() {
        return cancelReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    List<OrderLineEmbeddable> getLines() {
        return lines;
    }
}
