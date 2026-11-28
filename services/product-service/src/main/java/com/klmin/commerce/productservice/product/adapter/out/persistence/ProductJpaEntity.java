package com.klmin.commerce.productservice.product.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
class ProductJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false)
    private long price;

    @Column(name = "on_sale", nullable = false)
    private boolean onSale;

    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductJpaEntity() {
    }

    ProductJpaEntity(UUID id, String name, String description, long price, boolean onSale, String categoryCode,
                     Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.onSale = onSale;
        this.categoryCode = categoryCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void update(String name, String description, long price, boolean onSale, String categoryCode,
                Instant updatedAt) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.onSale = onSale;
        this.categoryCode = categoryCode;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    long getPrice() {
        return price;
    }

    boolean isOnSale() {
        return onSale;
    }

    String getCategoryCode() {
        return categoryCode;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
