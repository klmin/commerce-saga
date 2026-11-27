package com.klmin.commerce.pointservice.point.application.port.in;

import com.klmin.commerce.pointservice.common.error.DomainValidationException;

import java.util.UUID;

/**
 * 본인 포인트 내역 (최신순, 커서 페이징). 주문별 사용 기록과 지급 기록을 한 목록으로.
 *
 * @param before 이 항목보다 오래된 것만 (null 이면 처음부터). 항목 id(주문 id·지급 id)가 UUIDv7 이라 id 순서 = 생성 순서
 * @param size   1 ~ {@link #MAX_SIZE}
 */
public record ListMyPointHistoryQuery(UUID userId, UUID before, int size) {

    public static final int MAX_SIZE = 50;

    public ListMyPointHistoryQuery {
        if (size < 1 || size > MAX_SIZE) {
            throw new DomainValidationException("size must be between 1 and " + MAX_SIZE);
        }
    }
}
