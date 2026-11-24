package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.application.port.out.OrderIdempotencyPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 주문 Idempotency-Key. processed_message 와 같은 선점 방식이다: 뒤에 온 INSERT 는 앞 트랜잭션이 끝날 때까지
 * PK 인덱스에서 기다렸다가, 앞이 커밋하면 0건(그 기록을 읽음), 롤백하면 1건(이어받아 선점).
 * READ COMMITTED 라 기다린 뒤의 SELECT 는 앞 트랜잭션이 커밋한 행을 본다.
 */
@Component
class OrderIdempotencyPersistenceAdapter implements OrderIdempotencyPort {

    private static final String INSERT = """
            insert into order_idempotency_keys (user_id, idempotency_key, request_hash, order_id, created_at)
            values (?, ?, ?, ?, ?)
            on conflict (user_id, idempotency_key) do nothing
            """;

    private static final String SELECT = """
            select order_id, request_hash from order_idempotency_keys
            where user_id = ? and idempotency_key = ?
            """;

    private final JdbcClient jdbc;

    OrderIdempotencyPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<KeyRecord> claim(long userId, String key, String requestHash, UUID orderId, Instant now) {
        if (jdbc.sql(INSERT).params(userId, key, requestHash, orderId, Timestamp.from(now)).update() == 1) {
            return Optional.empty();
        }
        return Optional.of(jdbc.sql(SELECT).params(userId, key)
                .query((rs, n) -> new KeyRecord(rs.getObject("order_id", UUID.class), rs.getString("request_hash")))
                .single());
    }

    @Override
    public Optional<KeyRecord> find(long userId, String key) {
        return jdbc.sql(SELECT).params(userId, key)
                .query((rs, n) -> new KeyRecord(rs.getObject("order_id", UUID.class), rs.getString("request_hash")))
                .optional();
    }
}
