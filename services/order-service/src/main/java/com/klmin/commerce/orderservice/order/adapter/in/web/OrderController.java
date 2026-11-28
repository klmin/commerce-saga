package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.common.web.CurrentUserId;
import com.klmin.commerce.orderservice.order.application.port.in.CancelOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.CancelOrderResult;
import com.klmin.commerce.orderservice.order.application.port.in.CancelOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.ListMyOrdersQuery;
import com.klmin.commerce.orderservice.order.application.port.in.ListMyOrdersUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderResult;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final ListMyOrdersUseCase listMyOrdersUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;

    OrderController(PlaceOrderUseCase placeOrderUseCase, GetOrderUseCase getOrderUseCase,
                    ListMyOrdersUseCase listMyOrdersUseCase, CancelOrderUseCase cancelOrderUseCase) {
        this.cancelOrderUseCase = cancelOrderUseCase;
        this.placeOrderUseCase = placeOrderUseCase;
        this.getOrderUseCase = getOrderUseCase;
        this.listMyOrdersUseCase = listMyOrdersUseCase;
    }

    /**
     * 주문 생성. 응답 시점에는 PENDING 이고, 재고 예약 결과는 비동기로 반영된다.
     * <p>
     * Idempotency-Key 헤더(선택): 같은 키로 다시 보내면 새로 만들지 않고 처음 주문을 200 으로 돌려준다
     * ({@code Idempotent-Replayed: true}). 같은 키에 내용이 다르면 422.
     */
    @PostMapping
    ResponseEntity<OrderResponse> placeOrder(
            @CurrentUserId UUID userId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResult result = placeOrderUseCase.placeOrder(request.toCommand(userId, idempotencyKey));
        URI location = URI.create("/orders/" + result.order().id());
        OrderResponse body = OrderResponse.from(result.order());
        if (result.created()) {
            return ResponseEntity.created(location).body(body);
        }
        return ResponseEntity.status(HttpStatus.OK).location(location).header(IDEMPOTENT_REPLAYED, "true").body(body);
    }

    /** 본인 주문 목록 (최신순, 커서 페이징). 다음 쪽은 마지막 orderId 를 before 로 */
    @GetMapping
    List<OrderSummaryResponse> myOrders(
            @CurrentUserId UUID userId,
            @RequestParam(required = false) UUID before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ListMyOrdersQuery.MAX_SIZE) int size) {
        return listMyOrdersUseCase.listMyOrders(new ListMyOrdersQuery(userId, before, size)).stream()
                .map(OrderSummaryResponse::from)
                .toList();
    }

    @GetMapping("/{orderId}")
    OrderResponse getOrder(
            @CurrentUserId UUID userId,
            @PathVariable UUID orderId) {
        return OrderResponse.from(getOrderUseCase.getOrder(new GetOrderQuery(orderId, userId)));
    }

    /**
     * 주문 취소 (ADR 0020). 본인 주문만(남의 주문은 404). 진행 중이면 다음 응답에서, 완료됐으면 결제 취소(환불)부터 되돌린다.
     * 접수되면 202(결과는 주문 상태·Saga 타임라인으로 본다), 이미 취소된 주문이면 200. 여러 번 보내도 한 번만 반영된다
     */
    @PostMapping("/{orderId}/cancel")
    ResponseEntity<OrderResponse> cancelOrder(@CurrentUserId UUID userId, @PathVariable UUID orderId) {
        CancelOrderResult result = cancelOrderUseCase.cancelOrder(new CancelOrderCommand(orderId, userId));
        return ResponseEntity.status(result.inProgress() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(OrderResponse.from(result.order()));
    }
}
