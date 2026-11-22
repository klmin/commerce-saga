package com.klmin.commerce.inventoryservice.stock.application.port.in;

import java.util.UUID;

public interface ReserveStockUseCase {

    /**
     * 주문의 모든 항목 재고를 잡거나 하나도 잡지 않는다. 결과 응답은 같은 트랜잭션에서 outbox 에 남는다.
     * 같은 messageId 는 한 번만 처리한다(재전달은 응답도 다시 보내지 않음). 다른 메시지로 같은 주문이 다시 오면 재고는 그대로 두고 처음 결과를 다시 응답한다.
     */
    void reserve(UUID messageId, ReserveStockCommand command);
}
