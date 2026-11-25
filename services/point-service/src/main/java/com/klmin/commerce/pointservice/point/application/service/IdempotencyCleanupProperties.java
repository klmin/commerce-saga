package com.klmin.commerce.pointservice.point.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 멱등 기록 정리 (PROGRESS 4-4절 ②).
 *
 * @param processedMessageRetention processed_message 보존 기간. Kafka 토픽 보존(7일) + DLT 재처리 기간보다 길어야 한다
 *                                  (그 안에 같은 메시지가 다시 오면 기록이 있어야 거른다, ADR 0006)
 * @param batchSize                 한 번에 지우는 행 수 (긴 잠금·큰 WAL 을 피한다)
 * @param maxBatchesPerRun          한 번 돌 때 최대 배치 수 (밀린 행이 많아도 한 번에 오래 돌지 않게)
 */
@ConfigurationProperties("idempotency.cleanup")
record IdempotencyCleanupProperties(Duration processedMessageRetention,
                                    int batchSize, int maxBatchesPerRun) {
}
