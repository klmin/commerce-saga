package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.order.application.SagaNotFoundException;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderSagaUseCase;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderLine;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import com.klmin.commerce.orderservice.order.domain.Points;
import com.klmin.commerce.orderservice.order.domain.SagaEventType;
import com.klmin.commerce.orderservice.order.domain.UserId;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(com.klmin.commerce.orderservice.common.config.SecurityConfig.class)
@WebMvcTest(OrderSagaController.class)
class OrderSagaControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GetOrderSagaUseCase getOrderSagaUseCase;

    @Test
    void getSaga_returnsStateAndTimeline() throws Exception {
        Instant t0 = Instant.parse("2026-11-22T00:00:00Z");
        Order order = Order.place(UUID.randomUUID(), UserId.of(1L), List.of(OrderLine.of(pid(1), 1, 1_000L)), Points.ZERO, t0);
        OrderSaga saga = OrderSaga.start(order, t0);
        saga.handle(SagaEventType.STOCK_RESERVED, null, t0.plusSeconds(1));
        saga.handle(SagaEventType.PAYMENT_FAILED, "declined", t0.plusSeconds(2));
        given(getOrderSagaUseCase.getSaga(new GetOrderQuery(order.id(), 1L))).willReturn(saga);

        mvc.perform(get("/orders/{id}/saga", order.id()).with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RELEASING_STOCK"))
                .andExpect(jsonPath("$.compensating").value(true))
                .andExpect(jsonPath("$.failureReason").value("declined"))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[2].event").value("PAYMENT_FAILED"));
    }

    @Test
    void getSaga_notFoundOrNotOwned_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(getOrderSagaUseCase.getSaga(new GetOrderQuery(id, 2L))).willThrow(new SagaNotFoundException(id));

        mvc.perform(get("/orders/{id}/saga", id).with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isNotFound());
    }

    @Test
    void getSaga_withoutToken_returns401() throws Exception {
        mvc.perform(get("/orders/{id}/saga", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
