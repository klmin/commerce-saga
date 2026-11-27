package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import com.klmin.commerce.pointservice.point.domain.PointUsageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "point_usages")
class PointUsageJpaEntity {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PointUsageStatus status;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // null 이면 새 엔티티로 보고 persist 한다 (같은 orderId 가 이미 있으면 PK 위반으로 실패)
    @Version
    private Long version;

    protected PointUsageJpaEntity() {
    }

    PointUsageJpaEntity(UUID orderId, UUID userId, long amount, PointUsageStatus status, String reason,
                        Instant createdAt, Instant updatedAt) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void changeStatus(PointUsageStatus status, Instant updatedAt) {
        this.status = status;
        this.updatedAt = updatedAt;
    }

    UUID getOrderId() {
        return orderId;
    }

    UUID getUserId() {
        return userId;
    }

    long getAmount() {
        return amount;
    }

    PointUsageStatus getStatus() {
        return status;
    }

    String getReason() {
        return reason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
