package com.klmin.commerce.paymentservice.payment.adapter.out.persistence;

import com.klmin.commerce.paymentservice.payment.application.port.out.PgApproveAttemptPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * PG 승인 시도 기록. PG 호출 전에 커밋돼야 하므로 호출한 쪽 트랜잭션과 상관없이 새 트랜잭션에서 남긴다.
 * DB 장애로 남기지 못하면 예외 → PG 를 부르지 않고 메시지가 재시도된다
 */
@Component
class PgApproveAttemptPersistenceAdapter implements PgApproveAttemptPort {

    private static final String INSERT = """
            insert into pg_approve_attempts (order_id, attempted_at) values (?, ?)
            on conflict (order_id) do nothing
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    PgApproveAttemptPersistenceAdapter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordAttempt(UUID orderId) {
        return jdbc.sql(INSERT).params(orderId, Timestamp.from(clock.instant())).update() == 0;
    }
}
