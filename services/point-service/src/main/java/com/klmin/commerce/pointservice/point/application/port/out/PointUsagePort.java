package com.klmin.commerce.pointservice.point.application.port.out;

import com.klmin.commerce.pointservice.point.domain.PointUsage;

import java.util.Optional;
import java.util.UUID;

public interface PointUsagePort {

    Optional<PointUsage> findByOrderId(UUID orderId);

    /** 같은 orderId 가 이미 있으면 예외 (동시에 들어온 중복 커맨드 중 하나만 커밋된다) */
    void insert(PointUsage usage);

    /** 상태 변경을 반영한다. 동시에 다른 트랜잭션이 바꿨으면 낙관적 락 예외 (메시지는 재처리된다) */
    void update(PointUsage usage);
}
