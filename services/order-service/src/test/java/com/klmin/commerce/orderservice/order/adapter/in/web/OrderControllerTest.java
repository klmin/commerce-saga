package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.order.application.IdempotencyKeyReusedException;
import com.klmin.commerce.orderservice.order.application.OrderPriceChangedException;
import com.klmin.commerce.orderservice.order.application.ProductCatalogUnavailableException;
import com.klmin.commerce.orderservice.order.application.OrderNotFoundException;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.ListMyOrdersQuery;
import com.klmin.commerce.orderservice.order.application.port.in.ListMyOrdersUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.OrderSummaryResult;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderResult;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderLine;
import com.klmin.commerce.orderservice.order.domain.Points;
import com.klmin.commerce.orderservice.order.domain.UserId;
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

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static com.klmin.commerce.orderservice.TestUserIds.uid;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(com.klmin.commerce.orderservice.common.config.SecurityConfig.class)
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String VALID_BODY = """
            {"lines":[{"productId":"01900000-0000-7000-8000-000000000010","quantity":2,"unitPrice":1000}]}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PlaceOrderUseCase placeOrderUseCase;

    @MockitoBean
    GetOrderUseCase getOrderUseCase;

    @MockitoBean
    ListMyOrdersUseCase listMyOrdersUseCase;

    private static Order order(UUID userId) {
        return Order.place(UUID.randomUUID(), UserId.of(userId), List.of(OrderLine.of(pid(10), 2, 1_000L)), Points.ZERO,
                Instant.parse("2026-11-22T00:00:00Z"));
    }

    @Test
    void placeOrder_priceChanged_409_withCurrentPrices() throws Exception {
        given(placeOrderUseCase.placeOrder(any())).willThrow(new OrderPriceChangedException(
                List.of(new OrderPriceChangedException.CurrentPrice(pid(10), 1_200L))));

        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.currentPrices[0].productId").value(pid(10).toString()))
                .andExpect(jsonPath("$.currentPrices[0].price").value(1200));
    }

    @Test
    void placeOrder_productServiceDown_503_withoutInternalCause() throws Exception {
        given(placeOrderUseCase.placeOrder(any())).willThrow(
                new ProductCatalogUnavailableException(new RuntimeException("Connection refused: product-service")));

        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Connection refused"))));
    }

    @Test
    void placeOrder_returns201WithLocation() throws Exception {
        Order order = order(uid(7));
        given(placeOrderUseCase.placeOrder(new PlaceOrderCommand(uid(7),
                List.of(new PlaceOrderCommand.Line(pid(10), 2, 1_000L)), 0L))).willReturn(new PlaceOrderResult(order, true));

        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/" + order.id()))
                .andExpect(jsonPath("$.orderId").value(order.id().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(2000))
                .andExpect(jsonPath("$.paidAmount").value(2000));
    }

    @Test
    void placeOrder_withPoints_passesPointAmount() throws Exception {
        Order order = order(uid(7));
        given(placeOrderUseCase.placeOrder(new PlaceOrderCommand(uid(7),
                List.of(new PlaceOrderCommand.Line(pid(10), 2, 1_000L)), 500L))).willReturn(new PlaceOrderResult(order, true));

        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"productId":"01900000-0000-7000-8000-000000000010","quantity":2,"unitPrice":1000}],"pointAmount":500}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"productId":"01900000-0000-7000-8000-000000000010","quantity":2,"unitPrice":1000}],"pointAmount":-1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void placeOrder_withoutToken_returns401() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verify(placeOrderUseCase, never()).placeOrder(any());
    }

    @Test
    void placeOrder_withInvalidBody_returns400ProblemDetail() throws Exception {
        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"productId":"01900000-0000-7000-8000-000000000010","quantity":0,"unitPrice":1000}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());

        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}"))
                .andExpect(status().isBadRequest());

        // 금액 오버플로가 나는 단가는 500 이 아니라 400
        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"productId":"01900000-0000-7000-8000-000000000010","quantity":2,"unitPrice":9223372036854775807}]}
                                """))
                .andExpect(status().isBadRequest());

        // 상품 id 가 UUID 형식이 아니면 400
        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"productId":10,"quantity":2,"unitPrice":1000}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOrder_returnsOrder() throws Exception {
        Order order = order(uid(7));
        given(getOrderUseCase.getOrder(new GetOrderQuery(order.id(), uid(7)))).willReturn(order);

        mvc.perform(get("/orders/{id}", order.id()).with(jwt().jwt(j -> j.subject(uid(7).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.id().toString()))
                .andExpect(jsonPath("$.userId").value(uid(7).toString()));
    }

    @Test
    void getOrder_notFoundOrNotOwned_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(getOrderUseCase.getOrder(new GetOrderQuery(id, uid(8)))).willThrow(new OrderNotFoundException(id));

        mvc.perform(get("/orders/{id}", id).with(jwt().jwt(j -> j.subject(uid(8).toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void placeOrder_withIdempotencyKey_passesKey_andReplayReturns200() throws Exception {
        Order order = order(uid(7));
        PlaceOrderCommand command = new PlaceOrderCommand(uid(7),
                List.of(new PlaceOrderCommand.Line(pid(10), 2, 1_000L)), 0L, "key-1");
        given(placeOrderUseCase.placeOrder(command))
                .willReturn(new PlaceOrderResult(order, true), new PlaceOrderResult(order, false));

        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString()))).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString()))).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/orders/" + order.id()))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.orderId").value(order.id().toString()));
    }

    @Test
    void placeOrder_keyReusedWithDifferentBody_returns422() throws Exception {
        given(placeOrderUseCase.placeOrder(any())).willThrow(new IdempotencyKeyReusedException());

        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString()))).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void placeOrder_invalidIdempotencyKey_returns400() throws Exception {
        mvc.perform(post("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString()))).header("Idempotency-Key", "x".repeat(101))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest());

        verify(placeOrderUseCase, never()).placeOrder(any());
    }

    @Test
    void myOrders_usesTokenSubject_andCursor() throws Exception {
        UUID before = UUID.randomUUID();
        OrderSummaryResult summary = new OrderSummaryResult(UUID.randomUUID(), "COMPLETED", 2_000L, 500L, null,
                List.of(new OrderSummaryResult.Line(pid(10), 2, 1_000L)),
                Instant.parse("2026-11-22T00:00:00Z"), Instant.parse("2026-11-22T00:00:01Z"));
        given(listMyOrdersUseCase.listMyOrders(new ListMyOrdersQuery(uid(7), before, 10))).willReturn(List.of(summary));

        mvc.perform(get("/orders").param("before", before.toString()).param("size", "10")
                        .with(jwt().jwt(j -> j.subject(uid(7).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(summary.orderId().toString()))
                .andExpect(jsonPath("$[0].paidAmount").value(1500))
                .andExpect(jsonPath("$[0].lines[0].productId").value(pid(10).toString()));
    }

    @Test
    void myOrders_defaultsAndValidation() throws Exception {
        given(listMyOrdersUseCase.listMyOrders(new ListMyOrdersQuery(uid(7), null, 20))).willReturn(List.of());

        mvc.perform(get("/orders").with(jwt().jwt(j -> j.subject(uid(7).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/orders").param("size", "51").with(jwt().jwt(j -> j.subject(uid(7).toString()))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/orders").param("before", "not-a-uuid").with(jwt().jwt(j -> j.subject(uid(7).toString()))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/orders"))
                .andExpect(status().isUnauthorized());
    }
}
