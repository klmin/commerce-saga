package com.klmin.commerce.paymentservice.payment.application.port.out;

import java.util.UUID;

/**
 * 멱등 컨슈머의 메시지 처리 기록 (processed_message). 비즈니스 변경과 같은 트랜잭션 안에서만 부른다.
 * 트랜잭션이 롤백되면 기록도 사라지므로, 처리에 실패한 메시지는 재전달 때 다시 처리된다.
 */
public interface ProcessedMessagePort {

    /** 처음 보는 메시지면 기록하고 true, 이미 처리한 메시지면 false */
    boolean markProcessed(UUID messageId);

    /** 이미 처리한 메시지인지 (PG 호출 전 확인용. 최종 판단은 기록 트랜잭션의 markProcessed) */
    boolean isProcessed(UUID messageId);
}
