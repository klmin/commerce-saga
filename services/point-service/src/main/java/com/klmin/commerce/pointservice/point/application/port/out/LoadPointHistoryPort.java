package com.klmin.commerce.pointservice.point.application.port.out;

import com.klmin.commerce.pointservice.point.application.port.in.PointHistoryEntryResult;

import java.util.List;
import java.util.UUID;

public interface LoadPointHistoryPort {

    /** 사용자의 사용·지급 기록을 최신순(id 내림차순)으로. before 가 있으면 그보다 오래된 것만 */
    List<PointHistoryEntryResult> findByUser(UUID userId, UUID before, int size);
}
