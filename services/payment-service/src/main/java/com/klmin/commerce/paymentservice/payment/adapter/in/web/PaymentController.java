package com.klmin.commerce.paymentservice.payment.adapter.in.web;

import com.klmin.commerce.paymentservice.common.web.CurrentUserId;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentUseCase;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
class PaymentController {

    private final GetPaymentUseCase getPaymentUseCase;

    PaymentController(GetPaymentUseCase getPaymentUseCase) {
        this.getPaymentUseCase = getPaymentUseCase;
    }

    /** 주문의 결제 결과. 본인 결제가 아니면 404 */
    @GetMapping("/orders/{orderId}")
    PaymentResponse getByOrder(@CurrentUserId long userId,
                               @PathVariable UUID orderId) {
        Payment p = getPaymentUseCase.getPayment(new GetPaymentQuery(orderId, userId));
        return new PaymentResponse(p.id(), p.orderId(), p.amount(), p.status().name(), p.reason(), p.createdAt());
    }

    record PaymentResponse(UUID paymentId, UUID orderId, long amount, String status, String reason,
                           Instant createdAt) {
    }
}
