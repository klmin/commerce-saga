package com.klmin.commerce.outbox;

/**
 * Debezium relay 모드인데 이 DB 에 아직 replication slot·publication 이 없어 outbox 에 쓸 수 없다.
 * <p>
 * 이 모드는 outbox 행을 넣고 바로 지우므로, slot 이 생기기 전에 커밋하면 그 메시지는 WAL 에서 읽히지 않아
 * <b>영원히 발행되지 않는다</b>. 그래서 쓰기 전에 막고 트랜잭션을 롤백시킨다. 일시 오류다: Kafka 리스너는
 * 재시도(DLT 로 보내지 않음), 스케줄 작업은 다음 주기에 다시 하고, HTTP 요청은 실패로 끝나 다시 보내면 된다.
 * 커넥터가 slot 을 만들면 저절로 풀린다 (ADR 0004 위험 4, ADR 0014).
 */
public class OutboxNotReadyException extends RuntimeException {

    public OutboxNotReadyException(String message) {
        super(message);
    }
}
