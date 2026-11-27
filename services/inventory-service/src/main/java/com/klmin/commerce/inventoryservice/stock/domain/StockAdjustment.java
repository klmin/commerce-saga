package com.klmin.commerce.inventoryservice.stock.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 관리자 재고 조정 기록 한 줄 (추가 전용 로그, ADR 0019). 재고 자체는 {@link Stock} 애그리거트가 바꾸고,
 * 이 기록은 같은 트랜잭션에 남는다.
 *
 * @param id             저장 전이면 null (DB IDENTITY)
 * @param availableAfter 조정 직후 가용 재고
 * @param reason         사유 (없으면 null)
 * @param adjustedBy     조정한 관리자 userId (토큰의 sub)
 * @param idempotencyKey 관리자가 보낸 요청 키. 같은 관리자·같은 키면 한 번만 반영한다
 */
public record StockAdjustment(Long id, ProductId productId, int delta, long availableAfter, String reason,
                              UUID adjustedBy, String idempotencyKey, Instant adjustedAt) {

    public StockAdjustment {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(adjustedBy, "adjustedBy");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(adjustedAt, "adjustedAt");
        if (delta == 0 || availableAfter < 0) {
            throw new IllegalArgumentException("invalid stock adjustment: delta=" + delta
                    + ", availableAfter=" + availableAfter);
        }
    }

    /** 같은 키로 다시 온 요청이 처음과 같은 내용인지 (상품·수량·사유) */
    public boolean sameRequest(ProductId productId, int delta, String reason) {
        return this.productId.equals(productId) && this.delta == delta && Objects.equals(this.reason, reason);
    }
}
