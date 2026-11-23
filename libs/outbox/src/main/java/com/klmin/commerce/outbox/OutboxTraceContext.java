package com.klmin.commerce.outbox;

/**
 * outbox 에 메시지를 넣는 시점의 trace 문맥(W3C {@code traceparent})을 준다.
 * <p>
 * 저장한 값은 relay 가 Kafka 헤더 {@code traceparent} 로 그대로 싣는다(스케줄러는 직접, Debezium 은 Outbox Event Router 의
 * additional placement 로). 받는 쪽 리스너 observation 이 이 헤더를 이어받아, 주문 요청부터 Saga 의 마지막 응답까지가
 * trace 하나로 이어진다. relay 자체는 trace 를 만들지 않는다 (발행 지연은 메트릭으로 본다).
 */
@FunctionalInterface
public interface OutboxTraceContext {

    /** trace 를 쓰지 않을 때 */
    OutboxTraceContext NONE = () -> null;

    /** @return 현재 span 의 {@code traceparent}, 현재 span 이 없으면 null */
    String currentTraceparent();
}
