package com.klmin.commerce.orderservice.order.application;

import com.klmin.commerce.orderservice.common.error.ConflictException;

import java.util.List;
import java.util.Map;

/**
 * 화면에서 본 가격(요청의 unitPrice)과 지금 가격이 다름 (409, ADR 0016). 주문은 만들지 않았다.
 * 응답에 요청한 상품의 지금 가격을 실어 화면이 확인을 받고 새 가격으로 다시 보내게 한다.
 */
public class OrderPriceChangedException extends ConflictException {

    private final List<CurrentPrice> currentPrices;

    public OrderPriceChangedException(List<CurrentPrice> currentPrices) {
        super("상품 가격이 바뀌었습니다. 바뀐 가격을 확인한 뒤 다시 주문해 주세요.");
        this.currentPrices = List.copyOf(currentPrices);
    }

    public List<CurrentPrice> currentPrices() {
        return currentPrices;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("currentPrices", currentPrices);
    }

    public record CurrentPrice(long productId, long price) {
    }
}
