package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

import java.util.List;
import java.util.UUID;

/**
 * @param pointAmount    사용할 포인트 (0 이상 합계 이하). 나머지를 결제한다
 * @param idempotencyKey 클라이언트가 정한 요청 키 (없으면 null). 같은 사용자·같은 키면 주문을 한 번만 만든다
 * @param fromCart       장바구니 주문이면 true. 주문한 상품의 장바구니 줄을 주문과 같은 트랜잭션에서 지운다 (ADR 0016)
 */
public record PlaceOrderCommand(long userId, List<Line> lines, long pointAmount, String idempotencyKey,
                                boolean fromCart) {

    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

    public PlaceOrderCommand {
        lines = List.copyOf(lines);
        if (idempotencyKey != null && !isValidKey(idempotencyKey)) {
            throw new DomainValidationException("Idempotency-Key must be 1~" + IDEMPOTENCY_KEY_MAX_LENGTH
                    + " visible ASCII characters");
        }
    }

    public PlaceOrderCommand(long userId, List<Line> lines, long pointAmount, String idempotencyKey) {
        this(userId, lines, pointAmount, idempotencyKey, false);
    }

    public PlaceOrderCommand(long userId, List<Line> lines, long pointAmount) {
        this(userId, lines, pointAmount, null, false);
    }

    // 공백·제어 문자·비 ASCII 를 막아 로그·DB 에 이상한 값이 남지 않게 한다 (UUID 를 권장)
    private static boolean isValidKey(String key) {
        if (key.isEmpty() || key.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            return false;
        }
        return key.chars().allMatch(c -> c >= 0x21 && c <= 0x7E);
    }

    public record Line(UUID productId, int quantity, long unitPrice) {
    }
}
