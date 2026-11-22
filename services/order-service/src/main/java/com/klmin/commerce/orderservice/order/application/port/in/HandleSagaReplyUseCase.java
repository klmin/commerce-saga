package com.klmin.commerce.orderservice.order.application.port.in;

import java.util.UUID;

public interface HandleSagaReplyUseCase {

    /**
     * 응답을 Saga 에 반영하고 다음 커맨드·이벤트를 같은 트랜잭션에서 outbox 에 남긴다.
     * 같은 messageId 는 한 번만 처리하고, 현재 단계와 맞지 않는 응답(중복·늦은 응답)은 무시한다.
     */
    void handle(UUID messageId, SagaReplyCommand reply);
}
