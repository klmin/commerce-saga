package com.klmin.commerce.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * outbox relay 가 밀리고 있는지 보는 메트릭 (ADR 0013). 서비스는 자기 DB 만 본다.
 * <ul>
 *   <li>{@code commerce_outbox_pending_rows}, {@code commerce_outbox_oldest_pending_age_seconds}: 아직 발행 안 한 행.
 *       스케줄러 모드에서 relay 가 멈추거나 Kafka 에 못 보내면 늘어난다. Debezium 모드는 넣고 바로 지워 항상 0</li>
 *   <li>{@code commerce_outbox_replication_slot_lag_bytes}: 이 DB 의 logical slot 이 붙잡고 있는 WAL.
 *       Debezium 커넥터가 멈추거나 막히면 늘어나 디스크를 채운다 (ADR 0004, 시나리오 5). slot 이 없으면 0</li>
 * </ul>
 * 값은 스크레이프 때 조회하되 {@code cacheFor} 동안 재사용한다 (게이지마다 쿼리하지 않게).
 */
public class OutboxMetrics implements MeterBinder {

    private static final String PENDING = """
            select count(*) as rows,
                   coalesce(extract(epoch from (now() - min(created_at))), 0) as oldest_seconds
            from outbox
            """;

    private static final String SLOT_LAG = """
            select coalesce(max(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)), 0)
            from pg_replication_slots
            where database = current_database() and slot_type = 'logical'
            """;

    private final CachedValue<Snapshot> snapshot;

    public OutboxMetrics(JdbcClient jdbc, Clock clock, Duration cacheFor) {
        this.snapshot = new CachedValue<>(() -> load(jdbc), clock, cacheFor);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("commerce.outbox.pending.rows", snapshot, s -> s.get().pendingRows())
                .description("아직 발행하지 않은 outbox 행 수")
                .register(registry);
        TimeGauge.builder("commerce.outbox.oldest.pending.age", snapshot, TimeUnit.SECONDS,
                        s -> s.get().oldestPendingSeconds())
                .description("가장 오래된 미발행 outbox 행의 나이")
                .register(registry);
        Gauge.builder("commerce.outbox.replication.slot.lag", snapshot, s -> s.get().slotLagBytes())
                .description("이 DB 의 logical replication slot 이 붙잡고 있는 WAL")
                .baseUnit(BaseUnits.BYTES)
                .register(registry);
    }

    private static Snapshot load(JdbcClient jdbc) {
        Snapshot pending = jdbc.sql(PENDING)
                .query((rs, n) -> new Snapshot(rs.getLong("rows"), rs.getDouble("oldest_seconds"), 0))
                .single();
        double slotLag = jdbc.sql(SLOT_LAG).query(Double.class).single();
        return new Snapshot(pending.pendingRows(), pending.oldestPendingSeconds(), slotLag);
    }

    record Snapshot(long pendingRows, double oldestPendingSeconds, double slotLagBytes) {
    }
}
