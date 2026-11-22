package com.klmin.commerce.paymentservice.payment.application.service;

import com.klmin.commerce.paymentservice.payment.application.PaymentNotFoundException;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayFaultPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort.GatewayResult;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentPort;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제. 흐름: (1) 이미 처리한 주문이면 기록된 결과 재응답 (2) PG 승인 요청 - 트랜잭션 밖
 * (3) 결과 기록 + 응답 outbox - 한 트랜잭션.
 * <p>
 * (2) 후 (3) 전에 죽으면 메시지가 재처리되어 PG 를 다시 부르지만, PG 가 orderId 로 멱등이라 두 번 승인되지 않는다.
 * 결과를 알 수 없는 타임아웃(PG 는 승인했을 수 있음)을 조회로 보정하는 것은 3단계(시나리오 2).
 */
@Service
class PaymentService implements PayUseCase, GetPaymentUseCase, PaymentFaultUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRecorder recorder;
    private final PaymentGatewayPort gateway;
    private final PaymentGatewayFaultPort faultPort;
    private final PaymentPort paymentPort;

    PaymentService(PaymentRecorder recorder, PaymentGatewayPort gateway, PaymentGatewayFaultPort faultPort,
                   PaymentPort paymentPort) {
        this.recorder = recorder;
        this.gateway = gateway;
        this.faultPort = faultPort;
        this.paymentPort = paymentPort;
    }

    @Override
    public void pay(PayCommand command) {
        if (recorder.replyIfAlreadyProcessed(command.orderId())) {
            log.info("중복 결제 커맨드, 이전 결과 재응답 (orderId={})", command.orderId());
            return;
        }
        if (command.amount() <= 0) {
            recorder.replyInvalid(command.orderId(), "invalid command: amount must be positive");
            return;
        }
        GatewayResult result = gateway.approve(command.orderId(), command.userId(), command.amount());
        recorder.record(command, result);
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
