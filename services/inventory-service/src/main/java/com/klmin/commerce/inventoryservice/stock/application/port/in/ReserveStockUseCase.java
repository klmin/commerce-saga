package com.klmin.commerce.inventoryservice.stock.application.port.in;

public interface ReserveStockUseCase {

    /**
     * 주문의 모든 항목 재고를 잡거나 하나도 잡지 않는다. 결과 응답은 같은 트랜잭션에서 outbox 에 남는다.
     * 같은 주문으로 다시 호출되면 재고는 그대로 두고 처음 결과를 다시 응답한다.
     */
    void reserve(ReserveStockCommand command);
}
