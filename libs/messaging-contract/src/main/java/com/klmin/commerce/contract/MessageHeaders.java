package com.klmin.commerce.contract;

/**
 * Kafka 메시지 헤더 이름. 값(value)은 payload JSON 만 담고, 메시지 식별자·타입은 헤더로 보낸다.
 * Debezium Outbox Event Router 가 outbox 의 id 를 {@code id} 헤더로 내보내는 것과 맞춰서,
 * relay 를 스케줄러에서 Debezium 으로 바꿔도 컨슈머는 그대로 쓴다.
 */
public final class MessageHeaders {

    /** 메시지 식별자(UUID). 멱등 처리의 기준 */
    public static final String ID = "id";

    /** 메시지 타입 (예: ReserveStock) */
    public static final String TYPE = "type";

    private MessageHeaders() {
    }
}
