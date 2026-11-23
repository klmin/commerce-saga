package com.klmin.commerce.paymentservice.payment.application.port.in;

import java.time.Duration;

/**
 * 결제 보정 대상 통계 (메트릭용, ADR 0007·0013).
 *
 * @param unknown            PG 결과를 모르는 결제 수 (PG 타임아웃 후 조회로 확정 전)
 * @param oldestUnknownAge   그중 가장 오래된 것이 UNKNOWN 으로 기록된 뒤(PG 타임아웃) 지난 시간. 없으면 0
 * @param refundPending      취소 요청을 받았는데 승인 상태라 PG 환불을 기다리는 결제 수
 * @param oldestRefundPendingAge 그중 가장 오래된 것이 취소 요청을 받은 뒤 지난 시간. 없으면 0
 */
public record PaymentStatsResult(long unknown, Duration oldestUnknownAge, long refundPending,
                                 Duration oldestRefundPendingAge) {
}
