package com.klmin.commerce.productservice.product.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 상품 관리 이력. INSERT 만 한다. before_* 는 등록이면 null, 옮겨 온 옛 가격 이력은 가격만 있다 */
@Entity
@Table(name = "product_audit_log")
class ProductAuditLogJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private long productId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "before_name", updatable = false)
    private String beforeName;

    @Column(name = "before_description", updatable = false)
    private String beforeDescription;

    @Column(name = "before_price", updatable = false)
    private Long beforePrice;

    @Column(name = "before_on_sale", updatable = false)
    private Boolean beforeOnSale;

    @Column(name = "after_name", updatable = false)
    private String afterName;

    @Column(name = "after_description", updatable = false)
    private String afterDescription;

    @Column(name = "after_price", updatable = false)
    private Long afterPrice;

    @Column(name = "after_on_sale", updatable = false)
    private Boolean afterOnSale;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private long changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected ProductAuditLogJpaEntity() {
    }

    ProductAuditLogJpaEntity(long productId, String action, String beforeName, String beforeDescription,
                             Long beforePrice, Boolean beforeOnSale, String afterName, String afterDescription,
                             Long afterPrice, Boolean afterOnSale, long changedBy, Instant changedAt) {
        this.productId = productId;
        this.action = action;
        this.beforeName = beforeName;
        this.beforeDescription = beforeDescription;
        this.beforePrice = beforePrice;
        this.beforeOnSale = beforeOnSale;
        this.afterName = afterName;
        this.afterDescription = afterDescription;
        this.afterPrice = afterPrice;
        this.afterOnSale = afterOnSale;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    String getAction() {
        return action;
    }

    String getBeforeName() {
        return beforeName;
    }

    String getBeforeDescription() {
        return beforeDescription;
    }

    Long getBeforePrice() {
        return beforePrice;
    }

    Boolean getBeforeOnSale() {
        return beforeOnSale;
    }

    String getAfterName() {
        return afterName;
    }

    String getAfterDescription() {
        return afterDescription;
    }

    Long getAfterPrice() {
        return afterPrice;
    }

    Boolean getAfterOnSale() {
        return afterOnSale;
    }

    long getChangedBy() {
        return changedBy;
    }

    Instant getChangedAt() {
        return changedAt;
    }
}
