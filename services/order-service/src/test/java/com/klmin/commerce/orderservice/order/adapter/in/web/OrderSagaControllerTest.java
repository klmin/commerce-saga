package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.order.application.SagaNotFoundException;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderSagaUseCase;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderLine;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import com.klmin.commerce.orderservice.order.domain.SagaEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderSagaController.class)
class OrderSagaControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GetOrderSagaUseCase getOrderSagaUseCase;

    @Test
    void getSaga_returnsStateAndTimeline() throws Exception {
        Instant t0 = Instant.parse("2026-11-22T00:00:00Z");
        Order order = Order.place(UUID.randomUUID(), 1L, List.of(new OrderLine(1L, 1, 1_000L)), 0L, t0);
        OrderSaga saga = OrderSaga.start(order, t0);
        saga.handle(SagaEventType.STOCK_RESERVED, null, t0.plusSeconds(1));
        saga.handle(SagaEventType.PAYMENT_FAILED, "declined", t0.plusSeconds(2));
        given(getOrderSagaUseCase.getSaga(order.id())).willReturn(saga);

        mvc.perform(get("/admin/orders/{id}/saga", order.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RELEASING_STOCK"))
                .andExpect(jsonPath("$.compensating").value(true))
                .andExpect(jsonPath("$.failureReason").value("declined"))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[2].event").value("PAYMENT_FAILED"));
    }

    @Test
    void getSaga_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(getOrderSagaUseCase.getSaga(id)).willThrow(new SagaNotFoundException(id));

        mvc.perform(get("/admin/orders/{id}/saga", id)).andExpect(status().isNotFound());
    }
}
