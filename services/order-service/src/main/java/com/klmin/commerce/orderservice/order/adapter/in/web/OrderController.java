package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.common.web.CurrentUserId;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import com.klmin.commerce.orderservice.order.domain.Order;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
class OrderController {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;

    OrderController(PlaceOrderUseCase placeOrderUseCase, GetOrderUseCase getOrderUseCase) {
        this.placeOrderUseCase = placeOrderUseCase;
        this.getOrderUseCase = getOrderUseCase;
    }

    /** 주문 생성. 응답 시점에는 PENDING 이고, 재고 예약 결과는 비동기로 반영된다 */
    @PostMapping
    ResponseEntity<OrderResponse> placeOrder(
            @CurrentUserId long userId,
            @Valid @RequestBody PlaceOrderRequest request) {
        Order order = placeOrderUseCase.placeOrder(request.toCommand(userId));
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(OrderResponse.from(order));
    }

    @GetMapping("/{orderId}")
    OrderResponse getOrder(
            @CurrentUserId long userId,
            @PathVariable UUID orderId) {
        return OrderResponse.from(getOrderUseCase.getOrder(new GetOrderQuery(orderId, userId)));
    }
}
