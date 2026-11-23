package com.klmin.commerce.outbox;

import com.github.f4b6a3.uuid.UuidCreator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * outbox 테이블에 메시지를 저장한다. 발행은 relay(스케줄러 또는 Debezium)가 한다.
 * <p>
 * 비즈니스 데이터 변경과 같은 트랜잭션에서만 호출할 수 있다({@link Propagation#MANDATORY}).
 * 트랜잭션 밖에서 부르면 예외가 나므로 "DB 는 커밋됐는데 메시지는 없다" 같은 이중 쓰기가 생기지 않는다.
 * <p>
 * Debezium relay 모드({@code deleteAfterInsert = true})에서는 같은 트랜잭션에서 넣고 바로 지운다.
 * Debezium 은 WAL 의 INSERT 를 읽어 발행하므로 행이 테이블에 남을 필요가 없고(DELETE 는 Outbox Event Router 가 버린다),
 * 테이블이 쌓이지 않아 따로 정리할 일이 없다. 트랜잭션이 롤백되면 INSERT 도 WAL 에서 커밋되지 않아 발행되지 않는다.
 */
public class OutboxWriter {

    private static final String INSERT = """
            insert into outbox (id, aggregatetype, aggregateid, type, payload, traceparent)
            values (?, ?, ?, ?, ?::jsonb, ?)
            """;

    private static final String DELETE = "delete from outbox where id = ?";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final boolean deleteAfterInsert;
    private final OutboxTraceContext traceContext;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this(jdbc, json, false, OutboxTraceContext.NONE);
    }

    /**
     * @param deleteAfterInsert true 면 넣은 행을 같은 트랜잭션에서 지운다 (Debezium relay). 스케줄러 relay 는
     *                          테이블에서 행을 읽어 발행하므로 false 여야 한다
     */
    public OutboxWriter(JdbcClient jdbc, JsonMapper json, boolean deleteAfterInsert) {
        this(jdbc, json, deleteAfterInsert, OutboxTraceContext.NONE);
    }

    /**
     * @param traceContext 넣는 시점의 trace 문맥. 값이 있으면 outbox.traceparent 에 저장해 relay 가 헤더로 싣는다
     */
    public OutboxWriter(JdbcClient jdbc, JsonMapper json, boolean deleteAfterInsert, OutboxTraceContext traceContext) {
        this.jdbc = jdbc;
        this.json = json;
        this.deleteAfterInsert = deleteAfterInsert;
        this.traceContext = traceContext;
    }

    /**
     * @param topic   발행할 토픽 (outbox.aggregatetype)
     * @param key     Kafka 메시지 키 (outbox.aggregateid). 주문 관련 메시지는 orderId
     * @param type    메시지 타입 (헤더 {@code type})
     * @param payload 메시지 본문. JSON 으로 저장한다
     * @return 메시지 식별자 (헤더 {@code id})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String key, String type, Object payload) {
        UUID id = UuidCreator.getTimeOrderedEpoch(); // UUIDv7
        jdbc.sql(INSERT)
                .params(id, topic, key, type, json.writeValueAsString(payload), traceContext.currentTraceparent())
                .update();
        if (deleteAfterInsert) {
            jdbc.sql(DELETE).params(id).update();
        }
        return id;
    }
}
