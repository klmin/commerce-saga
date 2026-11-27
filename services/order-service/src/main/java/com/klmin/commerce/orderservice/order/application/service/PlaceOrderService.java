package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.OrderPriceChangedException;
import com.klmin.commerce.orderservice.order.application.ProductNotOrderableException;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderResult;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.out.ProductCatalogPort;
import com.klmin.commerce.orderservice.order.application.port.out.ProductCatalogPort.CatalogProduct;
import com.klmin.commerce.orderservice.order.domain.OrderLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 생성 입구 (ADR 0016). 트랜잭션을 열지 않는다: product-service 조회를 DB 트랜잭션 밖에서 하려고.
 * <ol>
 *   <li>같은 Idempotency-Key 의 재요청이면 처음 주문을 그대로 돌려준다 (가격이 그 뒤 바뀌었어도)</li>
 *   <li>요청 줄을 도메인 규칙으로 먼저 검사한다 (잘못된 요청은 product 를 부르지 않는다)</li>
 *   <li>product 에서 지금 가격을 한 번에 받아, 없거나 판매 중지면 422, 화면 가격과 다르면 409</li>
 *   <li>같으면 {@link OrderService#placeOrder} (한 트랜잭션: 키 선점 + 주문 + Saga + 재고 예약 커맨드)</li>
 * </ol>
 * 확인과 저장 사이에 관리자가 가격을 바꿀 수 있다. 그 주문은 확인한 가격(사용자가 본 가격)으로 들어간다.
 */
@Service
class PlaceOrderService implements PlaceOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    private final OrderService orderService;
    private final ProductCatalogPort productCatalogPort;

    PlaceOrderService(OrderService orderService, ProductCatalogPort productCatalogPort) {
        this.orderService = orderService;
        this.productCatalogPort = productCatalogPort;
    }

    @Override
    public PlaceOrderResult placeOrder(PlaceOrderCommand command) {
        if (command.idempotencyKey() != null) {
            Optional<PlaceOrderResult> replay = orderService.findReplay(command);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        command.lines().forEach(l -> OrderLine.of(l.productId(), l.quantity(), l.unitPrice()));
        verifyPrices(command);
        return orderService.placeOrder(command);
    }

    private void verifyPrices(PlaceOrderCommand command) {
        Set<UUID> productIds = command.lines().stream()
                .map(PlaceOrderCommand.Line::productId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, CatalogProduct> current = productCatalogPort.findByIds(productIds).stream()
                .collect(Collectors.toMap(CatalogProduct::productId, Function.identity(), (a, b) -> a));

        List<UUID> notOrderable = productIds.stream()
                .filter(id -> !current.containsKey(id) || !current.get(id).onSale())
                .toList();
        if (!notOrderable.isEmpty()) {
            throw new ProductNotOrderableException(notOrderable);
        }
        boolean changed = command.lines().stream()
                .anyMatch(l -> current.get(l.productId()).price() != l.unitPrice());
        if (changed) {
            List<OrderPriceChangedException.CurrentPrice> prices = new ArrayList<>();
            productIds.forEach(id -> prices.add(new OrderPriceChangedException.CurrentPrice(id, current.get(id).price())));
            log.info("주문 가격이 화면 가격과 다름, 409 (userId={}, currentPrices={})", command.userId(), prices);
            throw new OrderPriceChangedException(prices);
        }
    }
}
