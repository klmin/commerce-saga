package com.klmin.commerce.paymentservice.payment.adapter.in.web;

import com.klmin.commerce.paymentservice.common.web.CurrentUserId;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.ListMyPaymentsQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.ListMyPaymentsUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentSummaryResult;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
class PaymentController {

    private final GetPaymentUseCase getPaymentUseCase;
    private final ListMyPaymentsUseCase listMyPaymentsUseCase;

    PaymentController(GetPaymentUseCase getPaymentUseCase, ListMyPaymentsUseCase listMyPaymentsUseCase) {
        this.getPaymentUseCase = getPaymentUseCase;
        this.listMyPaymentsUseCase = listMyPaymentsUseCase;
    }

    /** 본인 결제 목록 (최신순, 커서 페이징). 다음 쪽은 마지막 paymentId 를 before 로 */
    @GetMapping
    List<PaymentResponse> myPayments(@CurrentUserId long userId,
                                     @RequestParam(required = false) UUID before,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(ListMyPaymentsQuery.MAX_SIZE)
                                     int size) {
        return listMyPaymentsUseCase.listMyPayments(new ListMyPaymentsQuery(userId, before, size)).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    /** 주문의 결제 결과. 본인 결제가 아니면 404 */
    @GetMapping("/orders/{orderId}")
    PaymentResponse getByOrder(@CurrentUserId long userId,
                               @PathVariable UUID orderId) {
        return PaymentResponse.from(getPaymentUseCase.getPayment(new GetPaymentQuery(orderId, userId)));
    }

    /** updatedAt 은 필드 추가 (결제 상태가 UNKNOWN → APPROVED, APPROVED → REFUNDED 로 바뀐 시각) */
    record PaymentResponse(UUID paymentId, UUID orderId, long amount, String status, String reason,
                           Instant createdAt, Instant updatedAt) {

        static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.id(), p.orderId(), p.amount(), p.status().name(), p.reason(),
                    p.createdAt(), p.updatedAt());
        }

        static PaymentResponse from(PaymentSummaryResult s) {
            return new PaymentResponse(s.paymentId(), s.orderId(), s.amount(), s.status(), s.reason(),
                    s.createdAt(), s.updatedAt());
        }
    }
}
