package com.klmin.commerce.paymentservice.payment.domain;

import com.klmin.commerce.paymentservice.common.error.DomainValidationException;

/**
 * 원화 금액 (값 객체). 0 이상. 결제 금액은 0 보다 커야 하는데 그 규칙은 {@link Payment} 가 가진다.
 * PG 호출처럼 사용자 id 와 금액이 같은 long 으로 나란히 가는 자리에서 순서를 바꿔 넘기는 실수를 타입으로 막는다.
 * <p>
 * 커맨드로 들어온 값은 서비스·리스너가 먼저 검사해 실패로 응답하거나 DLT 로 보낸다. 여기서 던지는 예외는 코드 버그다.
 */
public record Money(long value) {

    public Money {
        if (value < 0) {
            throw new DomainValidationException("money must not be negative: " + value);
        }
    }

    public static Money of(long value) {
        return new Money(value);
    }

    public boolean isPositive() {
        return value > 0;
    }
}
