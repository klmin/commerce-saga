package com.klmin.commerce.paymentservice.payment.adapter.in.web;

import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Mode;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가짜 PG 장애 주입 (시나리오 재현용). 결제 실패 → 포인트 환불 → 재고 해제 보상 흐름을 만들 때 쓴다.
 * 관리자 전용이어야 하지만 인증·인가는 4단계(ADMIN 역할)에서 붙인다.
 */
@RestController
@RequestMapping("/admin/payment-faults")
class PaymentFaultController {

    private final PaymentFaultUseCase paymentFaultUseCase;

    PaymentFaultController(PaymentFaultUseCase paymentFaultUseCase) {
        this.paymentFaultUseCase = paymentFaultUseCase;
    }

    @GetMapping
    FaultSettingsBody current() {
        Settings s = paymentFaultUseCase.current();
        return new FaultSettingsBody(s.mode(), s.delayMillis());
    }

    @PutMapping
    FaultSettingsBody change(@Valid @RequestBody FaultSettingsBody body) {
        paymentFaultUseCase.change(new Settings(body.mode(), body.delayMillis()));
        return current();
    }

    record FaultSettingsBody(
            @NotNull Mode mode,
            @PositiveOrZero @Max(Settings.MAX_DELAY_MILLIS) long delayMillis) {
    }
}
