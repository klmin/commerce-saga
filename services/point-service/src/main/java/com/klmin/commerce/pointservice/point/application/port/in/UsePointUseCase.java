package com.klmin.commerce.pointservice.point.application.port.in;

import java.util.UUID;

public interface UsePointUseCase {

    /**
     * 포인트를 차감하거나 하나도 차감하지 않고, 결과 응답을 같은 트랜잭션에서 outbox 에 남긴다.
     * 같은 messageId 는 한 번만 처리한다(재전달은 응답도 다시 보내지 않음). 다른 메시지로 같은 주문이 다시 오면 잔액은 그대로 두고 처음 결과를 다시 응답한다.
     */
    void use(UUID messageId, UsePointCommand command);
}
