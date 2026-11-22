package com.klmin.commerce.outbox;

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
 */
public class OutboxWriter {

    private static final String INSERT = """
            insert into outbox (id, aggregatetype, aggregateid, type, payload)
            values (?, ?, ?, ?, ?::jsonb)
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
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
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT)
                .params(id, topic, key, type, json.writeValueAsString(payload))
                .update();
        return id;
    }
}
