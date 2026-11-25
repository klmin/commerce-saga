package com.klmin.commerce.pointservice.point.application.service;

import com.klmin.commerce.pointservice.point.application.port.in.CleanupIdempotencyRecordsUseCase;
import com.klmin.commerce.pointservice.point.application.port.out.ProcessedMessageCleanupPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 보존 기간이 지난 멱등 기록을 나눠서 지운다. 비즈니스 기록(계정·사용·지급)은 지우지 않는다.
 * 배치마다 따로 커밋(문장 하나)이라 중간에 실패해도 지운 만큼은 남고 다음 실행이 이어서 지운다.
 * 여러 인스턴스에서 겹쳐 돌아도 결과가 같다
 */
@Service
@EnableConfigurationProperties(IdempotencyCleanupProperties.class)
class IdempotencyCleanupService implements CleanupIdempotencyRecordsUseCase {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupService.class);

    private final ProcessedMessageCleanupPort processedMessages;
    private final IdempotencyCleanupProperties properties;
    private final Clock clock;

    IdempotencyCleanupService(ProcessedMessageCleanupPort processedMessages,
                              IdempotencyCleanupProperties properties, Clock clock) {
        this.processedMessages = processedMessages;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public int cleanup() {
        Instant now = clock.instant();
        int messages = deleteInBatches((cutoff, limit) -> processedMessages.deleteProcessedBefore(
                cutoff, limit), now.minus(properties.processedMessageRetention()));
        if (messages > 0) {
            log.info("멱등 기록 정리: processed_message {}건", messages);
        }
        return messages;
    }

    private int deleteInBatches(BatchDelete delete, Instant cutoff) {
        int total = 0;
        for (int i = 0; i < properties.maxBatchesPerRun(); i++) {
            int deleted = delete.apply(cutoff, properties.batchSize());
            total += deleted;
            if (deleted < properties.batchSize()) {
                break;
            }
        }
        return total;
    }

    @FunctionalInterface
    private interface BatchDelete {
        int apply(Instant cutoff, int limit);
    }
}
