package com.klmin.commerce.pointservice.point.application.port.in;

import java.time.Instant;
import java.util.UUID;

/**
 * 포인트 내역 한 줄 (조회 전용 DTO).
 *
 * @param entryId 커서. 주문 사용이면 주문 id, 지급이면 지급 id
 * @param kind    {@code ORDER}(주문 사용) 또는 지급 종류({@code SIGNUP_BONUS})
 * @param orderId 주문 사용일 때만
 * @param status  주문 사용: USED·REJECTED·REFUNDED, 지급: GRANTED
 */
public record PointHistoryEntryResult(UUID entryId, String kind, UUID orderId, long amount, String status,
                                      String reason, Instant createdAt, Instant updatedAt) {
}
