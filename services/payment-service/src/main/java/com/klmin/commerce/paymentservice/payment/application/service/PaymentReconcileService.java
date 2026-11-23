package com.klmin.commerce.paymentservice.payment.application.service;

import com.klmin.commerce.paymentservice.payment.application.port.in.ReconcilePaymentsUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort.GatewayResult;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentPort;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import com.klmin.commerce.paymentservice.payment.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 결제 보정. PG 호출(조회·환불)은 트랜잭션 밖에서 하고, 결과 반영은 {@link PaymentRecorder} 가 결제 행을 잠근
 * 트랜잭션에서 다시 확인한 뒤 한다. 그래서 취소 요청과 PG 조회 결과가 동시에 와도 순서대로 반영되고,
 * 인스턴스가 여럿이어도 응답·환불이 두 번 일어나지 않는다 (PG 환불도 주문별로 멱등).
 * <ol>
 *   <li>UNKNOWN: PG 조회 → 승인·거절이면 확정. 취소 요청이 없으면 그때 응답(Saga 진행), 있으면 응답하지 않음.
 *       거래가 계속 없으면 {@code not-found-after} 뒤 거절로 확정</li>
 *   <li>취소 요청 + 승인(처음부터 또는 1에서 늦게 확인): PG 환불 → REFUNDED (늦은 승인 자동 환불)</li>
 * </ol>
 */
@Service
class PaymentReconcileService implements ReconcilePaymentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconcileService.class);

    private final PaymentPort paymentPort;
    private final PaymentGatewayPort gateway;
    private final PaymentRecorder recorder;
    private final PaymentReconcileProperties properties;
    private final Clock clock;

    PaymentReconcileService(PaymentPort paymentPort, PaymentGatewayPort gateway, PaymentRecorder recorder,
                            PaymentReconcileProperties properties, Clock clock) {
        this.paymentPort = paymentPort;
        this.gateway = gateway;
        this.recorder = recorder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<UUID> findPending() {
        return paymentPort.findPendingOrderIds(properties.batchSize());
    }

    @Override
    public void reconcile(UUID orderId) {
        Optional<Payment> found = paymentPort.findByOrderId(orderId);
        if (found.isEmpty()) {
            return;
        }
        Payment payment = found.get();
        if (payment.status() == PaymentStatus.UNKNOWN) {
            Optional<GatewayResult> result = gateway.inquire(orderId);
            if (result.isPresent()) {
                recorder.resolve(orderId, result.get());
            } else if (clock.instant().isAfter(payment.createdAt().plus(properties.notFoundAfter()))) {
                log.warn("PG 에 거래 없음, 거절로 확정 (orderId={})", orderId);
                recorder.resolve(orderId, GatewayResult.declined(
                        "no PG transaction after " + properties.notFoundAfter().toSeconds() + "s"));
            } else {
                return;
            }
            payment = paymentPort.findByOrderId(orderId).orElseThrow();
        }
        if (payment.needsRefund()) {
            gateway.refund(orderId);
            recorder.markRefunded(orderId);
        }
    }
}
