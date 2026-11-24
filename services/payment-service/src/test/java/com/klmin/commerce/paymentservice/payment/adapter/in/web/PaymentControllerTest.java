package com.klmin.commerce.paymentservice.payment.adapter.in.web;

import com.klmin.commerce.paymentservice.payment.application.PaymentNotFoundException;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.ListMyPaymentsQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.ListMyPaymentsUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentSummaryResult;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Mode;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import com.klmin.commerce.paymentservice.payment.domain.Money;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(com.klmin.commerce.paymentservice.common.config.SecurityConfig.class)
@WebMvcTest({PaymentController.class, PaymentFaultController.class})
class PaymentControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GetPaymentUseCase getPaymentUseCase;

    @MockitoBean
    ListMyPaymentsUseCase listMyPaymentsUseCase;

    @MockitoBean
    PaymentFaultUseCase paymentFaultUseCase;

    @Test
    void getByOrder_ownPayment() throws Exception {
        UUID orderId = UUID.randomUUID();
        Payment payment = Payment.approved(UUID.randomUUID(), orderId, 1L, Money.of(5_000L), "tx", Instant.now());
        given(getPaymentUseCase.getPayment(new GetPaymentQuery(orderId, 1L))).willReturn(payment);

        mvc.perform(get("/payments/orders/{id}", orderId).with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.amount").value(5000));
    }

    @Test
    void getByOrder_notOwned_returns404() throws Exception {
        UUID orderId = UUID.randomUUID();
        given(getPaymentUseCase.getPayment(new GetPaymentQuery(orderId, 2L)))
                .willThrow(new PaymentNotFoundException(orderId));

        mvc.perform(get("/payments/orders/{id}", orderId).with(jwt().jwt(j -> j.subject("2"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void myPayments_usesTokenSubject_andCursor() throws Exception {
        UUID before = UUID.randomUUID();
        PaymentSummaryResult summary = new PaymentSummaryResult(UUID.randomUUID(), UUID.randomUUID(), 5_000L, "REFUNDED",
                "refunded: order cancelled", Instant.parse("2026-11-23T00:00:00Z"),
                Instant.parse("2026-11-23T00:00:05Z"));
        given(listMyPaymentsUseCase.listMyPayments(new ListMyPaymentsQuery(1L, before, 5)))
                .willReturn(List.of(summary));

        mvc.perform(get("/payments").param("before", before.toString()).param("size", "5")
                        .with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(summary.paymentId().toString()))
                .andExpect(jsonPath("$[0].status").value("REFUNDED"))
                .andExpect(jsonPath("$[0].updatedAt").value("2026-11-23T00:00:05Z"));
    }

    @Test
    void myPayments_defaultsAndValidation() throws Exception {
        given(listMyPaymentsUseCase.listMyPayments(new ListMyPaymentsQuery(1L, null, 20))).willReturn(List.of());

        mvc.perform(get("/payments").with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/payments").param("size", "0").with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/payments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changeFaults() throws Exception {
        given(paymentFaultUseCase.current()).willReturn(new Settings(Mode.DECLINE, 0));

        mvc.perform(put("/admin/payment-faults").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"DECLINE\",\"delayMillis\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DECLINE"));
        verify(paymentFaultUseCase).change(new Settings(Mode.DECLINE, 0));
    }

    @Test
    void changeFaults_invalid_returns400() throws Exception {
        mvc.perform(put("/admin/payment-faults").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"DELAY\",\"delayMillis\":600000}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/admin/payment-faults").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delayMillis\":0}"))
                .andExpect(status().isBadRequest());
        verify(paymentFaultUseCase, never()).change(any());
    }

    @Test
    void changeFaults_requiresAdminRole() throws Exception {
        String body = "{\"mode\":\"DECLINE\",\"delayMillis\":0}";
        mvc.perform(put("/admin/payment-faults").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/admin/payment-faults").with(jwt().jwt(j -> j.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verify(paymentFaultUseCase, never()).change(any());
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("100")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
