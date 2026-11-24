package com.klmin.commerce.pointservice.point.domain;

import com.klmin.commerce.pointservice.common.error.DomainValidationException;

/**
 * 포인트 양 (값 객체). 0 이상이고 계산 결과도 음수가 되지 않는다. 넘치면 ArithmeticException.
 * <p>
 * 커맨드로 들어온 값은 {@code PointService} 가 먼저 검사해 실패로 응답한다. 여기서 던지는 예외는 코드 버그다.
 */
public record Points(long value) {

    public static final Points ZERO = new Points(0L);

    public Points {
        if (value < 0) {
            throw new DomainValidationException("points must not be negative: " + value);
        }
    }

    public static Points of(long value) {
        return new Points(value);
    }

    public Points plus(Points other) {
        return new Points(Math.addExact(value, other.value));
    }

    /** 결과가 음수면 DomainValidationException */
    public Points minus(Points other) {
        return new Points(value - other.value);
    }

    public boolean isPositive() {
        return value > 0;
    }

    public boolean isGreaterThan(Points other) {
        return value > other.value;
    }
}
