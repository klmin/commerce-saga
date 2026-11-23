package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.application.port.in.SagaStatsResult;
import com.klmin.commerce.orderservice.order.application.port.in.SagaStatsResult.StateResult;
import com.klmin.commerce.orderservice.order.application.port.out.LoadSagaStatsPort;
import com.klmin.commerce.orderservice.order.domain.SagaState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** 통계는 집계 쿼리로 바로 읽는다 (조회·통계는 DTO 직접 조회 허용) */
@Component
class SagaStatsPersistenceAdapter implements LoadSagaStatsPort {

    // 끝나지 않은 Saga 만 보므로 진행 중 부분 인덱스(V4)와 같은 조건
    private static final String ACTIVE = """
            select state, count(*) as n, min(created_at) as oldest
            from order_sagas
            where state not in ('COMPLETED', 'CANCELLED')
            group by state
            """;

    private static final String TIMEOUT_EVENTS = """
            select
              count(*) filter (where event = 'TIMEOUT_RESEND') as resends,
              count(*) filter (where event = 'TIMEOUT_GIVE_UP') as give_ups
            from order_saga_history
            where event in ('TIMEOUT_RESEND', 'TIMEOUT_GIVE_UP')
            """;

    private final JdbcClient jdbc;

    SagaStatsPersistenceAdapter(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public SagaStatsResult loadSagaStats(Instant now) {
        Map<SagaState, StateResult> active = new EnumMap<>(SagaState.class);
        jdbc.sql(ACTIVE).query(rs -> {
            Instant oldest = rs.getTimestamp("oldest").toInstant();
            active.put(SagaState.valueOf(rs.getString("state")),
                    new StateResult(rs.getLong("n"), nonNegative(Duration.between(oldest, now))));
        });
        return jdbc.sql(TIMEOUT_EVENTS)
                .query((rs, n) -> new SagaStatsResult(active, rs.getLong("resends"), rs.getLong("give_ups")))
                .single();
    }

    private static Duration nonNegative(Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }
}
