package com.klmin.commerce.inventoryservice.stock.application.port.in;

import com.klmin.commerce.inventoryservice.stock.domain.StockAdjustment;

import java.time.Instant;
import java.util.UUID;

/**
 * 조정 결과. available·reserved 는 응답 시점의 재고(같은 키로 다시 보낸 경우 그 사이 예약으로 바뀌었을 수 있다).
 *
 * @param replayed 같은 키로 다시 와서 새로 반영하지 않고 처음 기록을 돌려준 경우 true
 */
public record AdjustStockResult(UUID productId, long available, long reserved, Adjustment adjustment,
                                boolean replayed) {

    /** 조정 기록 한 줄 (응답·이력 목록 공용) */
    public record Adjustment(long id, int delta, long availableAfter, String reason, UUID adjustedBy,
                             Instant adjustedAt) {

        public static Adjustment from(StockAdjustment a) {
            return new Adjustment(a.id(), a.delta(), a.availableAfter(), a.reason(), a.adjustedBy(), a.adjustedAt());
        }
    }
}
