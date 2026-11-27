package com.klmin.commerce.inventoryservice.stock.application.port.in;

import com.klmin.commerce.inventoryservice.common.error.DomainValidationException;

import java.util.Objects;
import java.util.UUID;

/**
 * 관리자 재고 조정 (ADR 0019).
 *
 * @param delta          입고는 양수, 차감은 음수. 0 은 안 되고 한 번에 {@link #MAX_DELTA} 까지
 * @param reason         사유 (선택). 앞뒤 공백을 지우고, 비면 null
 * @param adminId        조정한 관리자 = 토큰의 sub
 * @param idempotencyKey 관리자 화면이 요청마다 만든 키. 같은 관리자·같은 키면 한 번만 반영한다
 */
public record AdjustStockCommand(UUID productId, int delta, String reason, UUID adminId, String idempotencyKey) {

    public static final int MAX_DELTA = 1_000_000;
    public static final int MAX_REASON_LENGTH = 200;
    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

    public AdjustStockCommand {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(adminId, "adminId");
        if (delta == 0 || Math.abs((long) delta) > MAX_DELTA) {
            throw new DomainValidationException("조정 수량은 0 이 아닌 -" + MAX_DELTA + " ~ " + MAX_DELTA + " 사이여야 합니다.");
        }
        reason = reason == null || reason.isBlank() ? null : reason.strip();
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw new DomainValidationException("사유는 " + MAX_REASON_LENGTH + "자까지입니다.");
        }
        if (idempotencyKey == null || !isValidKey(idempotencyKey)) {
            throw new DomainValidationException("Idempotency-Key must be 1~" + IDEMPOTENCY_KEY_MAX_LENGTH
                    + " visible ASCII characters");
        }
    }

    // 공백·제어 문자·비 ASCII 를 막아 로그·DB 에 이상한 값이 남지 않게 한다 (UUID 를 권장)
    private static boolean isValidKey(String key) {
        if (key.isEmpty() || key.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            return false;
        }
        return key.chars().allMatch(c -> c >= 0x21 && c <= 0x7E);
    }
}
