package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import com.klmin.commerce.pointservice.point.application.port.out.PointUsagePort;
import com.klmin.commerce.pointservice.point.domain.PointUsage;
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
        repository.save(new PointUsageJpaEntity(usage.orderId(), usage.userId(), usage.amount(), usage.status(),
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
        return PointUsage.restore(e.getOrderId(), e.getUserId(), e.getAmount(), e.getStatus(), e.getReason(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
