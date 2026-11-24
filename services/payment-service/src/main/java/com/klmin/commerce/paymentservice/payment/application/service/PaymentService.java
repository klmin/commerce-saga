package com.klmin.commerce.paymentservice.payment.application.service;

import com.klmin.commerce.paymentservice.payment.application.PaymentGatewayTimeoutException;
import com.klmin.commerce.paymentservice.payment.application.PaymentGatewayUnavailableException;
import com.klmin.commerce.paymentservice.payment.application.PaymentNotFoundException;
import com.klmin.commerce.paymentservice.payment.application.port.in.CancelPaymentCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.CancelPaymentUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayFaultPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort.GatewayResult;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.ProcessedMessagePort;
import com.klmin.commerce.paymentservice.payment.domain.Money;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 결제. 흐름: (0) 같은 메시지의 재전달이면 건너뜀 (1) 이미 처리한 주문이면 기록된 결과 재응답
 * (2) PG 승인 요청 - 트랜잭션 밖
 * (3) 결과 기록 + 응답 outbox - 한 트랜잭션. PG 타임아웃이면 UNKNOWN 으로만 기록하고 응답하지 않는다
 * (결제 보정이 PG 조회로 확정한 뒤 응답, ADR 0007).
 * <p>
 * (2) 후 (3) 전에 죽으면 메시지가 재처리되어 PG 를 다시 부르지만, PG 가 orderId 로 멱등이라 두 번 승인되지 않는다.
 */
@Service
class PaymentService implements PayUseCase, CancelPaymentUseCase, GetPaymentUseCase, PaymentFaultUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    static final String CIRCUIT_OPEN_REASON = "PG unavailable (circuit open)";

    private final PaymentRecorder recorder;
    private final PaymentGatewayPort gateway;
    private final PaymentGatewayFaultPort faultPort;
    private final PaymentPort paymentPort;
    private final ProcessedMessagePort processedMessagePort;

    PaymentService(PaymentRecorder recorder, PaymentGatewayPort gateway, PaymentGatewayFaultPort faultPort,
                   PaymentPort paymentPort, ProcessedMessagePort processedMessagePort) {
        this.recorder = recorder;
        this.gateway = gateway;
        this.faultPort = faultPort;
        this.paymentPort = paymentPort;
        this.processedMessagePort = processedMessagePort;
    }

    @Override
    public void pay(UUID messageId, PayCommand command) {
        // 같은 메시지의 재전달이면 PG 도 부르지 않는다. 최종 판단은 기록 트랜잭션의 markProcessed 가 한다
        if (processedMessagePort.isProcessed(messageId)) {
            log.info("이미 처리한 메시지 건너뜀: Pay (messageId={}, orderId={})", messageId, command.orderId());
            return;
        }
        if (recorder.replyIfAlreadyProcessed(messageId, command.orderId())) {
            log.info("중복 결제 커맨드, 이전 결과 재응답 (orderId={})", command.orderId());
            return;
        }
        if (command.amount() <= 0) {
            recorder.replyInvalid(messageId, command.orderId(), "invalid command: amount must be positive");
            return;
        }
        GatewayResult result;
        try {
            result = gateway.approve(command.orderId(), command.userId(), Money.of(command.amount()));
        } catch (PaymentGatewayTimeoutException e) {
            recorder.recordUnknown(messageId, command);
            return;
        } catch (PaymentGatewayUnavailableException e) {
            // 서킷 브레이커가 열려 PG 를 부르지 않았다 → 확정 실패. Saga 가 바로 보상한다 (4-4절 ③)
            log.warn("PG 회로 열림, 결제 즉시 실패 (orderId={})", command.orderId());
            result = GatewayResult.declined(CIRCUIT_OPEN_REASON);
        }
        recorder.record(messageId, command, result);
    }

    @Override
    public void cancel(UUID messageId, CancelPaymentCommand command) {
        recorder.cancel(messageId, command);
    }

    @Override
    @Transactional(readOnly = true)
    public Payment getPayment(GetPaymentQuery query) {
        return paymentPort.findByOrderId(query.orderId())
                .filter(payment -> payment.isOwnedBy(query.userId()))
                .orElseThrow(() -> new PaymentNotFoundException(query.orderId()));
    }

    @Override
    public Settings current() {
        return faultPort.current();
    }

    @Override
    public void change(Settings settings) {
        log.warn("가짜 PG 장애 주입 변경: {}", settings);
        faultPort.change(settings);
    }
}
