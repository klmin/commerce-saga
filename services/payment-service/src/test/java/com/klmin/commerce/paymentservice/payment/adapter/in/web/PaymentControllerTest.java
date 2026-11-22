package com.klmin.commerce.paymentservice.payment.adapter.in.web;

import com.klmin.commerce.paymentservice.payment.application.PaymentNotFoundException;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Mode;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PaymentController.class, PaymentFaultController.class})
class PaymentControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GetPaymentUseCase getPaymentUseCase;

    @MockitoBean
    PaymentFaultUseCase paymentFaultUseCase;

    @Test
    void getByOrder_ownPayment() throws Exception {
        UUID orderId = UUID.randomUUID();
        Payment payment = Payment.approved(UUID.randomUUID(), orderId, 1L, 5_000L, "tx", Instant.now());
        given(getPaymentUseCase.getPayment(new GetPaymentQuery(orderId, 1L))).willReturn(payment);

        mvc.perform(get("/payments/orders/{id}", orderId).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.amount").value(5000));
    }

    @Test
    void getByOrder_notOwned_returns404() throws Exception {
        UUID orderId = UUID.randomUUID();
        given(getPaymentUseCase.getPayment(new GetPaymentQuery(orderId, 2L)))
                .willThrow(new PaymentNotFoundException(orderId));

        mvc.perform(get("/payments/orders/{id}", orderId).header("X-User-Id", "2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void changeFaults() throws Exception {
        given(paymentFaultUseCase.current()).willReturn(new Settings(Mode.DECLINE, 0));

        mvc.perform(put("/admin/payment-faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"DECLINE\",\"delayMillis\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DECLINE"));
        verify(paymentFaultUseCase).change(new Settings(Mode.DECLINE, 0));
    }

    @Test
    void changeFaults_invalid_returns400() throws Exception {
        mvc.perform(put("/admin/payment-faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"DELAY\",\"delayMillis\":600000}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/admin/payment-faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delayMillis\":0}"))
                .andExpect(status().isBadRequest());
        verify(paymentFaultUseCase, never()).change(any());
    }
}
