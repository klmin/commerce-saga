package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.domain.SagaState;
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
@Table(name = "order_sagas")
class OrderSagaJpaEntity {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "point_amount", nullable = false)
    private long pointAmount;

    @Column(name = "paid_amount", nullable = false)
    private long paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SagaState state;

    @Column(name = "point_used", nullable = false)
    private boolean pointUsed;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "step_resends", nullable = false)
    private int stepResends;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // null 이면 새 엔티티로 보고 persist 한다
    @Version
    private Long version;

    @ElementCollection
    @CollectionTable(name = "order_saga_history", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "seq")
    private List<SagaHistoryEmbeddable> history = new ArrayList<>();

    protected OrderSagaJpaEntity() {
    }

    OrderSagaJpaEntity(UUID orderId, long userId, long pointAmount, long paidAmount, SagaState state,
                       boolean pointUsed, String failureReason, int stepResends, Instant createdAt,
                       Instant updatedAt, List<SagaHistoryEmbeddable> history) {
        this.orderId = orderId;
        this.userId = userId;
        this.pointAmount = pointAmount;
        this.paidAmount = paidAmount;
        this.state = state;
        this.pointUsed = pointUsed;
        this.failureReason = failureReason;
        this.stepResends = stepResends;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.history = new ArrayList<>(history);
    }

    /** 진행 기록은 뒤에 붙기만 하므로 저장된 것 이후의 항목만 추가한다 */
    void change(SagaState state, boolean pointUsed, String failureReason, int stepResends, Instant updatedAt,
                List<SagaHistoryEmbeddable> fullHistory) {
        this.state = state;
        this.pointUsed = pointUsed;
        this.failureReason = failureReason;
        this.stepResends = stepResends;
        this.updatedAt = updatedAt;
        for (int i = history.size(); i < fullHistory.size(); i++) {
            history.add(fullHistory.get(i));
        }
    }

    UUID getOrderId() {
        return orderId;
    }

    long getUserId() {
        return userId;
    }

    long getPointAmount() {
        return pointAmount;
    }

    long getPaidAmount() {
        return paidAmount;
    }

    SagaState getState() {
        return state;
    }

    boolean isPointUsed() {
        return pointUsed;
    }

    String getFailureReason() {
        return failureReason;
    }

    int getStepResends() {
        return stepResends;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    List<SagaHistoryEmbeddable> getHistory() {
        return history;
    }
}
