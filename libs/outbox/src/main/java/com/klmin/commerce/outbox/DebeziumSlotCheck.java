package com.klmin.commerce.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Debezium relay 모드에서 이 DB 에 logical replication slot 이 있는지 기동 때 확인한다.
 * <p>
 * 이 모드의 {@link OutboxWriter} 는 행을 넣고 바로 지우므로, slot(커넥터)이 만들어지기 전에 쓴 outbox 는
 * WAL 에서 읽을 수 없어 발행되지 않는다. 서비스는 outbox 테이블(Flyway)을 만든 뒤 커넥터가 등록되어야 하므로
 * 기동을 막지는 않고 경고만 남긴다. 운영 순서: 서비스 기동 → 커넥터 등록 → 트래픽.
 */
class DebeziumSlotCheck implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DebeziumSlotCheck.class);

    private static final String COUNT_SLOTS = """
            select count(*) from pg_replication_slots
            where database = current_database() and slot_type = 'logical'
            """;

    private final JdbcClient jdbc;

    DebeziumSlotCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            long slots = jdbc.sql(COUNT_SLOTS).query(Long.class).single();
            if (slots == 0) {
                log.warn("outbox.relay.enabled=false(Debezium relay) 인데 이 DB 에 logical replication slot 이 없다. "
                        + "커넥터를 등록하기 전에 쓴 outbox 메시지는 발행되지 않는다 (scripts/register-connectors.sh)");
            } else {
                log.info("Debezium relay 모드: logical replication slot {}개 확인", slots);
            }
        } catch (RuntimeException e) {
            log.warn("replication slot 확인 실패: {}", e.toString());
        }
    }
}
