package com.klmin.commerce.productservice.product.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 카테고리 고정 목록 (Flyway 시드, 앱은 읽기만) */
@Entity
@Table(name = "categories")
class CategoryJpaEntity {

    @Id
    @Column(length = 30)
    private String code;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected CategoryJpaEntity() {
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }
}
