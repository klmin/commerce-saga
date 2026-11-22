package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.order.application.OrderNotFoundException;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String VALID_BODY = """
            {"lines":[{"productId":10,"quantity":2,"unitPrice":1000}]}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PlaceOrderUseCase placeOrderUseCase;

    @MockitoBean
    GetOrderUseCase getOrderUseCase;

    private static Order order(long userId) {
        return Order.place(UUID.randomUUID(), userId, List.of(new OrderLine(10L, 2, 1_000L)),
                Instant.parse("2026-11-22T00:00:00Z"));
    }

    @Test
    void placeOrder_returns201WithLocation() throws Exception {
        Order order = order(7L);
        given(placeOrderUseCase.placeOrder(new PlaceOrderCommand(7L,
                List.of(new PlaceOrderCommand.Line(10L, 2, 1_000L))))).willReturn(order);

        mvc.perform(post("/orders").header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/" + order.id()))
                .andExpect(jsonPath("$.orderId").value(order.id().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(2000));
    }

    @Test
    void placeOrder_withoutUserIdHeader_returns400() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest());

        verify(placeOrderUseCase, never()).placeOrder(any());
    }

    @Test
    void placeOrder_withInvalidUserId_returns400() throws Exception {
        mvc.perform(post("/orders").header("X-User-Id", "0")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void placeOrder_withInvalidBody_returns400ProblemDetail() throws Exception {
        mvc.perform(post("/orders").header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"productId":10,"quantity":0,"unitPrice":1000}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());

        mvc.perform(post("/orders").header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOrder_returnsOrder() throws Exception {
        Order order = order(7L);
        given(getOrderUseCase.getOrder(new GetOrderQuery(order.id(), 7L))).willReturn(order);

        mvc.perform(get("/orders/{id}", order.id()).header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.id().toString()))
                .andExpect(jsonPath("$.userId").value(7));
    }

    @Test
    void getOrder_notFoundOrNotOwned_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(getOrderUseCase.getOrder(new GetOrderQuery(id, 8L))).willThrow(new OrderNotFoundException(id));

        mvc.perform(get("/orders/{id}", id).header("X-User-Id", "8"))
                .andExpect(status().isNotFound());
    }
}
