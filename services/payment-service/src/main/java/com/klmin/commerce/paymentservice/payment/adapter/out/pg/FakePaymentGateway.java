package com.klmin.commerce.paymentservice.payment.adapter.out.pg;

import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Mode;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayFaultPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 가짜 PG. 실제 PG 처럼 orderId 를 멱등 키로 다뤄 같은 주문의 재요청에는 처음 결과를 돌려준다(이중 승인 없음).
 * 장애 주입 모드(승인·거절·지연)는 실행 중에 바꿀 수 있다. 상태는 메모리에만 있다 (PG 쪽 기록을 흉내 낸 것).
 */
@Component
class FakePaymentGateway implements PaymentGatewayPort, PaymentGatewayFaultPort {

    private final AtomicReference<Settings> settings;
    private final Map<UUID, GatewayResult> results = new ConcurrentHashMap<>();

    FakePaymentGateway(@Value("${fake-pg.mode:APPROVE}") Mode mode,
                       @Value("${fake-pg.delay-millis:0}") long delayMillis) {
        this.settings = new AtomicReference<>(new Settings(mode, delayMillis));
    }

    @Override
    public GatewayResult approve(UUID orderId, long userId, long amount) {
        return results.computeIfAbsent(orderId, id -> decide(settings.get()));
    }

    private static GatewayResult decide(Settings current) {
        return switch (current.mode()) {
            case APPROVE -> GatewayResult.approved(transactionId());
            case DECLINE -> GatewayResult.declined("declined by PG (fault injection)");
            case DELAY -> {
                sleep(current.delayMillis());
                yield GatewayResult.approved(transactionId());
            }
        };
    }

    private static String transactionId() {
        return "fake-" + UUID.randomUUID();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PG 응답 대기 중 인터럽트", e);
        }
    }

    @Override
    public Settings current() {
        return settings.get();
    }

    @Override
    public void change(Settings newSettings) {
        settings.set(newSettings);
    }
}
