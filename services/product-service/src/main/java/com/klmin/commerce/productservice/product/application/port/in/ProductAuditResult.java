package com.klmin.commerce.productservice.product.application.port.in;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리 이력 한 건. action = REGISTERED·DESCRIBED·PRICE_CHANGED·SALE_STATUS_CHANGED.
 * before 는 등록이면 null. 관리 이력 이전의 가격 변경(가격 이력에서 옮긴 것)은 가격만 있고 나머지 값은 null.
 * categoryCode·imageKey 는 그 열(ADR 0021) 전의 이력이면 null
 */
public record ProductAuditResult(String action, Snapshot before, Snapshot after, UUID changedBy, Instant changedAt) {

    public record Snapshot(String name, String description, Long price, Boolean onSale, String categoryCode,
                           String imageKey) {
    }
}
