package com.klmin.commerce.paymentservice.payment.adapter.out.persistence;

import com.klmin.commerce.paymentservice.payment.application.port.out.PgApproveAttemptPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * PG 승인 호출 예약·봉인. PG 호출 전에 커밋돼야 하므로 호출한 쪽 트랜잭션과 상관없이 새 트랜잭션에서 한다.
 * 예약(calls + 1, 봉인 안 된 경우만)과 봉인(calls = 1 인 경우만)은 같은 행의 조건부 갱신이라 동시에 와도 행 잠금으로
 * 차례가 정해진다: 봉인이 먼저면 뒤의 예약은 실패하고, 예약이 먼저면 봉인이 실패한다.
 * DB 장애로 예약하지 못하면 예외 → PG 를 부르지 않고 메시지가 재시도된다
 */
@Component
class PgApproveAttemptPersistenceAdapter implements PgApproveAttemptPort {

    private static final String CLAIM = """
            insert into pg_approve_attempts (order_id, calls, sealed, attempted_at) values (?, 1, false, ?)
            on conflict (order_id) do update
                set calls = pg_approve_attempts.calls + 1, attempted_at = excluded.attempted_at
                where not pg_approve_attempts.sealed
            returning calls
            """;

    private static final String SEAL = """
            update pg_approve_attempts set sealed = true where order_id = ? and calls = 1 and not sealed
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    PgApproveAttemptPersistenceAdapter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OptionalInt claim(UUID orderId) {
        return jdbc.sql(CLAIM).params(orderId, Timestamp.from(clock.instant()))
                .query(Integer.class).optional()
                .map(OptionalInt::of).orElse(OptionalInt.empty());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sealIfOnlyClaim(UUID orderId) {
        return jdbc.sql(SEAL).param(orderId).update() == 1;
    }
}
