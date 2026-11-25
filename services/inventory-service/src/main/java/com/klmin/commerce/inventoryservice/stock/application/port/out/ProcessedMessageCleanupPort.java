package com.klmin.commerce.inventoryservice.stock.application.port.out;

import java.time.Instant;

/** 보존 기간이 지난 processed_message 정리 (ADR 0006, PROGRESS 4-4절 ②) */
public interface ProcessedMessageCleanupPort {

    /**
     * processed_at 이 cutoff 보다 이른 행을 오래된 것부터 최대 limit 개 지운다.
     * 여러 인스턴스가 동시에 돌아도 같은 행을 두고 기다리지 않는다(잠긴 행은 건너뜀). 결과는 같다
     *
     * @return 지운 행 수
     */
    int deleteProcessedBefore(Instant cutoff, int limit);
}
