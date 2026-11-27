package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.common.web.CurrentUserId;
import com.klmin.commerce.orderservice.order.application.port.in.CartResult;
import com.klmin.commerce.orderservice.order.application.port.in.CartUseCase;
import com.klmin.commerce.orderservice.order.domain.OrderLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 내 장바구니 (후속 4-1c, ADR 0016). 사용자는 토큰의 sub 뿐이라 남의 장바구니에 닿을 경로가 없다.
 * 장바구니 주문은 POST /orders 에 fromCart=true (주문한 상품의 줄이 같은 트랜잭션에서 빠진다)
 */
@RestController
@RequestMapping("/cart")
class CartController {

    private final CartUseCase cartUseCase;

    CartController(CartUseCase cartUseCase) {
        this.cartUseCase = cartUseCase;
    }

    @GetMapping
    CartResponse get(@CurrentUserId UUID userId) {
        return CartResponse.from(cartUseCase.get(userId));
    }

    /** 담기 또는 수량 바꾸기 (수량은 더하지 않고 그 값으로) */
    @PutMapping("/items/{productId}")
    CartResponse put(@CurrentUserId UUID userId, @PathVariable UUID productId,
                     @Valid @RequestBody PutItemRequest request) {
        return CartResponse.from(cartUseCase.put(userId, productId, request.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    CartResponse remove(@CurrentUserId UUID userId, @PathVariable UUID productId) {
        return CartResponse.from(cartUseCase.remove(userId, productId));
    }

    record PutItemRequest(@NotNull @Positive @Max(OrderLine.MAX_QUANTITY) Integer quantity) {
    }

    record CartResponse(List<Item> items, boolean pricesAvailable) {

        record Item(UUID productId, String name, int quantity, long addedPrice, Long currentPrice, Boolean onSale,
                    boolean priceChanged) {
        }

        static CartResponse from(CartResult r) {
            return new CartResponse(r.items().stream()
                    .map(i -> new Item(i.productId(), i.name(), i.quantity(), i.addedPrice(), i.currentPrice(),
                            i.onSale(), i.priceChanged()))
                    .toList(), r.pricesAvailable());
        }
    }
}
