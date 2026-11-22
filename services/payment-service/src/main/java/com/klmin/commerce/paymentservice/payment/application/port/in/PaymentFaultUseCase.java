package com.klmin.commerce.paymentservice.payment.application.port.in;

import java.util.Objects;

/** 가짜 PG 장애 주입 설정 조회·변경 (시연·시나리오용 관리자 API, 권한 검사는 4단계) */
public interface PaymentFaultUseCase {

    Settings current();

    void change(Settings settings);

    /**
     * @param mode        APPROVE(승인) | DECLINE(거절) | DELAY(delayMillis 만큼 지연 후 승인)
     * @param delayMillis DELAY 일 때 지연 시간
     */
    record Settings(Mode mode, long delayMillis) {

        public static final long MAX_DELAY_MILLIS = 60_000L;

        public Settings {
            Objects.requireNonNull(mode, "mode");
            if (delayMillis < 0 || delayMillis > MAX_DELAY_MILLIS) {
                throw new IllegalArgumentException("delayMillis must be between 0 and " + MAX_DELAY_MILLIS);
            }
        }
    }

    enum Mode {
        APPROVE, DECLINE, DELAY
    }
}
