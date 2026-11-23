package com.klmin.commerce.paymentservice.payment.application.service;

import com.klmin.commerce.paymentservice.payment.application.port.in.ListMyPaymentsQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.ListMyPaymentsUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentSummaryResult;
import com.klmin.commerce.paymentservice.payment.application.port.out.LoadPaymentSummariesPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 결제 목록 조회. 쓰기가 아니라 애그리거트를 거치지 않고 DTO 로 바로 읽는다 */
@Service
class PaymentQueryService implements ListMyPaymentsUseCase {

    private final LoadPaymentSummariesPort summariesPort;

    PaymentQueryService(LoadPaymentSummariesPort summariesPort) {
        this.summariesPort = summariesPort;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentSummaryResult> listMyPayments(ListMyPaymentsQuery query) {
        return summariesPort.findByUser(query.userId(), query.before(), query.size());
    }
}
