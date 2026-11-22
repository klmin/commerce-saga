package com.klmin.commerce.contract;

/**
 * 토픽 이름.
 * <ul>
 *   <li>{@code <서비스>.command}: 그 서비스가 받는 커맨드. 수신 서비스 소유, 그 서비스만 구독</li>
 *   <li>{@code <서비스>.reply}: 커맨드에 대한 응답. 오케스트레이터(order-service)만 구독</li>
 *   <li>{@code <서비스>.events}: 발행 서비스가 알리는 사실. 누구나 구독</li>
 * </ul>
 * 메시지 키는 모두 orderId 다 (주문 단위 순서 보장).
 * 처리할 수 없는 메시지는 소비자가 {@code <토픽>.DLT} 로 옮긴다.
 */
public final class Topics {

    public static final String INVENTORY_COMMAND = "inventory.command";
    public static final String INVENTORY_REPLY = "inventory.reply";
    public static final String POINT_COMMAND = "point.command";
    public static final String POINT_REPLY = "point.reply";
    public static final String PAYMENT_COMMAND = "payment.command";
    public static final String PAYMENT_REPLY = "payment.reply";
    public static final String ORDER_EVENTS = "order.events";

    private Topics() {
    }
}
