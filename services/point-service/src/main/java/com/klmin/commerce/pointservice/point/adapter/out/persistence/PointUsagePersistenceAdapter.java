package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import com.klmin.commerce.pointservice.point.application.port.out.PointUsagePort;
import com.klmin.commerce.pointservice.point.domain.PointUsage;
import com.klmin.commerce.pointservice.point.domain.Points;
import com.klmin.commerce.pointservice.point.domain.UserId;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class PointUsagePersistenceAdapter implements PointUsagePort {

    private final PointUsageJpaRepository repository;

    PointUsagePersistenceAdapter(PointUsageJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PointUsage> findByOrderId(UUID orderId) {
        return repository.findById(orderId).map(PointUsagePersistenceAdapter::toDomain);
    }

    @Override
    public void insert(PointUsage usage) {
        repository.save(new PointUsageJpaEntity(usage.orderId(),
                usage.userId() == null ? null : usage.userId().value(), usage.amount().value(), usage.status(),
                usage.reason(), usage.createdAt(), usage.updatedAt()));
    }

    // 같은 트랜잭션에서 조회한 영속 엔티티에 값만 옮긴다 (@Version 으로 동시 변경 감지)
    @Override
    public void update(PointUsage usage) {
        PointUsageJpaEntity entity = repository.findById(usage.orderId())
                .orElseThrow(() -> new IllegalStateException("point usage not found: " + usage.orderId()));
        entity.changeStatus(usage.status(), usage.updatedAt());
    }

    private static PointUsage toDomain(PointUsageJpaEntity e) {
        // 값 객체 도입 전에는 잘못된 커맨드의 거절 기록에 userId 0 이하·음수 금액이 그대로 남았다. 그런 값은 비워서
        // 읽는다(거절 기록은 차감·환불에 쓰지 않음). 복원이 실패하면 같은 주문의 RefundPoint(보상)가 계속 재시도된다
        Long userId = e.getUserId();
        return PointUsage.restore(e.getOrderId(), userId != null && userId > 0 ? UserId.of(userId) : null,
                Points.of(Math.max(e.getAmount(), 0L)), e.getStatus(), e.getReason(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
