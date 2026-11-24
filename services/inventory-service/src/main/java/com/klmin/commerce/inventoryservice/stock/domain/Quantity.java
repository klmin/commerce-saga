package com.klmin.commerce.inventoryservice.stock.domain;

/**
 * 예약·해제·확정하는 수량 (값 객체). 1 이상.
 * 재고 잔량(available·reserved)은 0 이 될 수 있어 이 타입이 아니라 {@link Stock} 안의 int 로 둔다.
 * <p>
 * 커맨드로 들어온 값은 {@code StockService} 가 먼저 검사해 실패로 응답한다. 여기서 던지는 예외는 코드 버그다.
 */
public record Quantity(int value) {

    public Quantity {
        if (value <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + value);
        }
    }

    public static Quantity of(int value) {
        return new Quantity(value);
    }
}
