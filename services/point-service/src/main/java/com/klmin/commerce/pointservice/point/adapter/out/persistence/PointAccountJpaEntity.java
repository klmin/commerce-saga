package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "point_accounts")
class PointAccountJpaEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private long balance;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PointAccountJpaEntity() {
    }

    void change(long balance, Instant updatedAt) {
        this.balance = balance;
        this.updatedAt = updatedAt;
    }

    Long getUserId() {
        return userId;
    }

    long getBalance() {
        return balance;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
