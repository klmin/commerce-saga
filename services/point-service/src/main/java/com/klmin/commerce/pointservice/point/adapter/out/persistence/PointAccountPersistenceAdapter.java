package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import com.klmin.commerce.pointservice.point.application.port.out.PointAccountPort;
import com.klmin.commerce.pointservice.point.domain.PointAccount;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class PointAccountPersistenceAdapter implements PointAccountPort {

    private final PointAccountJpaRepository repository;

    PointAccountPersistenceAdapter(PointAccountJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PointAccount> findById(long userId) {
        return repository.findById(userId).map(PointAccountPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<PointAccount> findByIdForUpdate(long userId) {
        return repository.findByIdForUpdate(userId).map(PointAccountPersistenceAdapter::toDomain);
    }

    // 같은 트랜잭션에서 잠가 조회한 엔티티(1차 캐시)에 값만 옮긴다. 커밋 때 더티 체킹으로 UPDATE
    @Override
    public void update(PointAccount account) {
        PointAccountJpaEntity entity = repository.findById(account.userId())
                .orElseThrow(() -> new IllegalStateException("point account not found: " + account.userId()));
        entity.change(account.balance(), account.updatedAt());
    }

    private static PointAccount toDomain(PointAccountJpaEntity e) {
        return PointAccount.restore(e.getUserId(), e.getBalance(), e.getUpdatedAt());
    }
}
