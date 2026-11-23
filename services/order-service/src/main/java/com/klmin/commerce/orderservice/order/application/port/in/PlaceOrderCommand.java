package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

import java.util.List;

/**
 * @param pointAmount    사용할 포인트 (0 이상 합계 이하). 나머지를 결제한다
 * @param idempotencyKey 클라이언트가 정한 요청 키 (없으면 null). 같은 사용자·같은 키면 주문을 한 번만 만든다
 */
public record PlaceOrderCommand(long userId, List<Line> lines, long pointAmount, String idempotencyKey) {

    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

    public PlaceOrderCommand {
        lines = List.copyOf(lines);
        if (idempotencyKey != null && !isValidKey(idempotencyKey)) {
            throw new DomainValidationException("Idempotency-Key must be 1~" + IDEMPOTENCY_KEY_MAX_LENGTH
                    + " visible ASCII characters");
        }
    }

    public PlaceOrderCommand(long userId, List<Line> lines, long pointAmount) {
        this(userId, lines, pointAmount, null);
    }

    // 공백·제어 문자·비 ASCII 를 막아 로그·DB 에 이상한 값이 남지 않게 한다 (UUID 를 권장)
    private static boolean isValidKey(String key) {
        if (key.isEmpty() || key.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            return false;
        }
        return key.chars().allMatch(c -> c >= 0x21 && c <= 0x7E);
    }

    public record Line(long productId, int quantity, long unitPrice) {
    }
}
