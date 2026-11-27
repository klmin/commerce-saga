package com.klmin.commerce.productservice.product.domain;

import com.klmin.commerce.productservice.common.error.DomainValidationException;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 상품 애그리거트. 가격은 0 보다 커야 하고, 바꾸면 {@link PriceChange} 를 돌려줘 같은 트랜잭션에 이력을 남긴다.
 * 판매 중지 상품은 조회는 되지만 주문할 수 없다(order 가 422).
 */
public final class Product {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    private final ProductId id;
    private String name;
    private String description;
    private Money price;
    private boolean onSale;
    private final Instant createdAt;
    private Instant updatedAt;

    private Product(ProductId id, String name, String description, Money price, boolean onSale,
                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.onSale = onSale;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 새 상품. id 는 앱이 저장 전에 만든 UUIDv7 (ADR 0018) */
    public static Product register(ProductId id, String name, String description, Money price, Instant now) {
        Objects.requireNonNull(id, "id");
        requirePositive(price);
        return new Product(id,validName(name), validDescription(description), price, true, now, now);
    }

    public static Product restore(ProductId id, String name, String description, Money price, boolean onSale,
                                  Instant createdAt, Instant updatedAt) {
        return new Product(Objects.requireNonNull(id, "id"), name, description, price, onSale, createdAt, updatedAt);
    }

    /** 이름·설명 수정. 같은 값이면 아무것도 바꾸지 않는다 */
    public void describe(String name, String description, Instant now) {
        String newName = validName(name);
        String newDescription = validDescription(description);
        if (!newName.equals(this.name) || !newDescription.equals(this.description)) {
            this.name = newName;
            this.description = newDescription;
            this.updatedAt = now;
        }
    }

    /** 관리 이력에 남길 지금 값 */
    public ProductSnapshot snapshot() {
        return new ProductSnapshot(name, description, price, onSale);
    }

    /** 가격 변경. 같은 가격이면 아무것도 바꾸지 않고 이력도 남기지 않는다 */
    public Optional<PriceChange> changePrice(Money newPrice, UserId changedBy, Instant now) {
        requirePositive(newPrice);
        Objects.requireNonNull(changedBy, "changedBy");
        if (newPrice.equals(price)) {
            return Optional.empty();
        }
        PriceChange change = new PriceChange(id, price, newPrice, changedBy, now);
        this.price = newPrice;
        this.updatedAt = now;
        return Optional.of(change);
    }

    public void changeSaleStatus(boolean onSale, Instant now) {
        if (this.onSale != onSale) {
            this.onSale = onSale;
            this.updatedAt = now;
        }
    }

    private static void requirePositive(Money price) {
        if (price == null || !price.isPositive()) {
            throw new DomainValidationException("price must be positive");
        }
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw new DomainValidationException("name must not be blank");
        }
        String trimmed = name.strip();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new DomainValidationException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String validDescription(String description) {
        String value = description == null ? "" : description.strip();
        if (value.length() > MAX_DESCRIPTION_LENGTH) {
            throw new DomainValidationException(
                    "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return value;
    }

    public ProductId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Money price() {
        return price;
    }

    public boolean onSale() {
        return onSale;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
