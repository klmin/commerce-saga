package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.application.port.out.ProcessedMessageCleanupPort;
import com.klmin.commerce.orderservice.order.application.port.out.ProcessedMessagePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * processed_message 기록. 호출한 서비스의 트랜잭션에 참여한다 (MANDATORY: 트랜잭션 밖 호출은 오류).
 * 같은 메시지가 동시에 두 번 처리되면 뒤쪽 INSERT 가 앞쪽 커밋을 기다렸다가 충돌로 0건이 되어 건너뛴다.
 */
@Component
class ProcessedMessagePersistenceAdapter implements ProcessedMessagePort, ProcessedMessageCleanupPort {

    private static final String INSERT = """
            insert into processed_message (message_id, processed_at) values (?, ?)
            on conflict (message_id) do nothing
            """;

    // 오래된 것부터 limit 개. 다른 인스턴스가 잠근 행은 건너뛴다 (결과는 같고 서로 기다리지 않는다)
    private static final String DELETE_OLD = """
            delete from processed_message where message_id in (
                select message_id from processed_message where processed_at < ?
                order by processed_at limit ? for update skip locked)
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    ProcessedMessagePersistenceAdapter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID messageId) {
        return jdbc.sql(INSERT).params(messageId, Timestamp.from(clock.instant())).update() == 1;
    }

    @Override
    public int deleteProcessedBefore(Instant cutoff, int limit) {
        return jdbc.sql(DELETE_OLD).params(Timestamp.from(cutoff), limit).update();
    }
}
