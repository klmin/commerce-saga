package com.klmin.commerce.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 폴링 방식 outbox relay. 한 트랜잭션에서
 * <ol>
 *   <li>outbox 행을 {@code FOR UPDATE SKIP LOCKED} 로 잠가 가져오고 (다른 인스턴스와 같은 행을 나눠 갖지 않음)</li>
 *   <li>Kafka 로 보낸 뒤 모든 ack 를 기다리고</li>
 *   <li>보낸 행을 지운다</li>
 * </ol>
 * 전송이 하나라도 실패하면 롤백되어 행이 남고 다음 주기에 다시 보낸다.
 * ack 를 받은 뒤 삭제 전에 죽으면 같은 메시지가 다시 나간다(at-least-once). 컨슈머가 멱등으로 처리한다.
 * <p>
 * 한계: 순서는 created_at(트랜잭션 시작 시각) 기준이라 커밋 순서와 다를 수 있고, 인스턴스가 여럿이면
 * 같은 키의 메시지가 서로 다른 인스턴스에서 나가 순서가 바뀔 수 있다. Debezium relay 로 바꾸면 해소된다 (ADR 0004).
 */
public class SchedulerOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(SchedulerOutboxRelay.class);

    private static final String SELECT = """
            select id, aggregatetype, aggregateid, type, payload::text as payload
            from outbox
            order by created_at
            limit :limit
            for update skip locked
            """;

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxRelayProperties props;

    public SchedulerOutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                                OutboxRelayProperties props) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
    }

    /** 스케줄러가 주기적으로 부른다. 실패는 로그만 남기고 다음 주기에 다시 시도한다 */
    public void relayOnSchedule() {
        try {
            relay();
        } catch (RuntimeException e) {
            log.warn("outbox relay 실패, 다음 주기에 다시 시도: {}", e.toString());
        }
    }

    /** @return 발행하고 삭제한 행 수 */
    public int relay() {
        Integer count = tx.execute(status -> relayBatch());
        return count == null ? 0 : count;
    }

    private int relayBatch() {
        List<OutboxRow> rows = jdbc.sql(SELECT)
                .param("limit", props.batchSize())
                .query(OutboxRow.class)
                .list();
        if (rows.isEmpty()) {
            return 0;
        }

        // 순서대로 보내고(프로듀서 멱등성으로 파티션 안 순서 유지) ack 는 한꺼번에 기다린다
        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(rows.size());
        for (OutboxRow row : rows) {
            futures.add(kafka.send(toRecord(row)));
        }
        for (CompletableFuture<SendResult<String, String>> future : futures) {
            await(future);
        }

        List<UUID> ids = rows.stream().map(OutboxRow::id).toList();
        jdbc.sql("delete from outbox where id in (:ids)")
                .param("ids", ids)
                .update();
        log.debug("outbox {}건 발행", rows.size());
        return rows.size();
    }

    private void await(CompletableFuture<SendResult<String, String>> future) {
        try {
            future.get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("outbox 발행 대기 중 인터럽트", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("outbox 발행 실패", e);
        }
    }

    private static ProducerRecord<String, String> toRecord(OutboxRow row) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(row.aggregatetype(), row.aggregateid(), row.payload());
        record.headers().add("id", row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("type", row.type().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    record OutboxRow(UUID id, String aggregatetype, String aggregateid, String type, String payload) {
    }
}
