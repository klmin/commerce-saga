package com.klmin.commerce.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Debezium relay 모드에서 outbox 에 써도 되는지: 이 DB 에 logical replication slot 이 있고, outbox 테이블이
 * publication 에 들어 있어야 한다. 둘 중 하나라도 없을 때 넣고 바로 지운 행은 커넥터가 읽지 못한다.
 * <p>
 * 준비된 뒤에는 {@code ttl} 동안 결과를 재사용하고(쓰기마다 조회하지 않게), 준비 전에는 매번 조회한다(slot 이 생기면 바로 풀리게).
 * slot 을 지우면(커넥터 삭제 등) ttl 안에 다시 막힌다.
 */
final class DebeziumRelayReadiness implements RelayReadiness {

    private static final String QUERY = """
            select exists (select 1 from pg_replication_slots
                           where database = current_database() and slot_type = 'logical')
               and exists (select 1 from pg_publication_tables
                           where schemaname = current_schema() and tablename = 'outbox')
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration ttl;
    private Instant readyUntil = Instant.MIN;

    DebeziumRelayReadiness(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC(), Duration.ofSeconds(5));
    }

    DebeziumRelayReadiness(JdbcClient jdbc, Clock clock, Duration ttl) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public synchronized boolean ready() {
        Instant now = clock.instant();
        if (now.isBefore(readyUntil)) {
            return true;
        }
        boolean ready = Boolean.TRUE.equals(jdbc.sql(QUERY).query(Boolean.class).single());
        readyUntil = ready ? now.plus(ttl) : Instant.MIN;
        return ready;
    }
}
