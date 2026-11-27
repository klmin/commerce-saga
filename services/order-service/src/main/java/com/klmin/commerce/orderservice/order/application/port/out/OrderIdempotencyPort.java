package com.klmin.commerce.orderservice.order.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 주문 Idempotency-Key 기록 ((user_id, key) 유니크) */
public interface OrderIdempotencyPort {

    /**
     * 키를 이 주문에 선점한다. 호출한 트랜잭션에 참여한다.
     * <p>
     * 같은 키를 다른 트랜잭션이 동시에 선점 중이면 그 트랜잭션이 끝날 때까지 기다린다. 앞이 커밋하면 그 기록을,
     * 롤백하면 이 호출이 선점한다.
     *
     * @return 선점했으면 empty, 이미 있으면 그 기록
     */
    Optional<KeyRecord> claim(UUID userId, String key, String requestHash, UUID orderId, Instant now);

    /** 이미 선점된 키의 기록 (선점하지 않고 읽기만) */
    Optional<KeyRecord> find(UUID userId, String key);

    record KeyRecord(UUID orderId, String requestHash) {
    }
}
