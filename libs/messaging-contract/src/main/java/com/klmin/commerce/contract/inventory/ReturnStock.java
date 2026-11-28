package com.klmin.commerce.contract.inventory;

import java.util.UUID;

/**
 * 커맨드(order -> inventory, 완료 후 취소): 이 주문으로 팔린 재고를 가용 재고로 다시 들여라 (ADR 0020).
 * {@link ReleaseStock}(잡은 예약을 푼다)과 달리 판매 확정된 수량도 되돌린다. 아직 확정 전이면 예약을 풀어 같은 결과가 된다.
 * 예약 기록 기준으로 되돌리므로 여러 번 와도, 되돌릴 것이 없어도 안전하다 (항상 StockReturned 응답).
 */
public record ReturnStock(UUID orderId) {

    public static final String TYPE = "ReturnStock";
}
