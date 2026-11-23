package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import com.klmin.commerce.pointservice.point.application.port.out.PointAccountPort;
import com.klmin.commerce.pointservice.point.domain.PointAccount;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;

@Component
class PointAccountPersistenceAdapter implements PointAccountPort {

    private static final String INSERT_IF_ABSENT = """
            insert into point_accounts (user_id, balance, updated_at) values (?, ?, ?)
            on conflict (user_id) do nothing
            """;

    private final PointAccountJpaRepository repository;
    private final JdbcClient jdbc;

    PointAccountPersistenceAdapter(PointAccountJpaRepository repository, JdbcClient jdbc) {
        this.jdbc = jdbc;
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

    // 같은 사용자 계정을 동시에 만들면 늦은 쪽은 앞 트랜잭션이 끝날 때까지 기다렸다가 0건이 된다
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(PointAccount account) {
        return jdbc.sql(INSERT_IF_ABSENT)
                .params(account.userId(), account.balance(), Timestamp.from(account.updatedAt()))
                .update() == 1;
    }

    private static PointAccount toDomain(PointAccountJpaEntity e) {
        return PointAccount.restore(e.getUserId(), e.getBalance(), e.getUpdatedAt());
    }
}
