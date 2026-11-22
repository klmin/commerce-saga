package com.klmin.commerce.pointservice.point.application.port.in;

public interface UsePointUseCase {

    /**
     * 포인트를 차감하거나 하나도 차감하지 않고, 결과 응답을 같은 트랜잭션에서 outbox 에 남긴다.
     * 같은 주문으로 다시 호출되면 잔액은 그대로 두고 처음 결과를 다시 응답한다.
     */
    void use(UsePointCommand command);
}
