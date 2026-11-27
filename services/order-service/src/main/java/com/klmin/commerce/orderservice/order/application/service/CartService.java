package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.ProductCatalogUnavailableException;
import com.klmin.commerce.orderservice.order.application.ProductNotOrderableException;
import com.klmin.commerce.orderservice.order.application.port.in.CartResult;
import com.klmin.commerce.orderservice.order.application.port.in.CartUseCase;
import com.klmin.commerce.orderservice.order.application.port.out.CartPort;
import com.klmin.commerce.orderservice.order.application.port.out.ProductCatalogPort;
import com.klmin.commerce.orderservice.order.application.port.out.ProductCatalogPort.CatalogProduct;
import com.klmin.commerce.orderservice.order.domain.Cart;
import com.klmin.commerce.orderservice.order.domain.CartItem;
import com.klmin.commerce.orderservice.order.domain.Money;
import com.klmin.commerce.orderservice.order.domain.ProductId;
import com.klmin.commerce.orderservice.order.domain.Quantity;
import com.klmin.commerce.orderservice.order.domain.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 장바구니. product-service 조회는 트랜잭션 밖에서 한다 (ADR 0016). 쓰기는 문장 하나(upsert·delete)라 트랜잭션을 열지 않는다.
 * 동시에 담으면 최대 줄 수를 한 줄 넘을 수 있다(서로의 INSERT 를 못 봄). 주문 줄 수 검사가 다시 막으므로 잠그지 않는다
 */
@Service
class CartService implements CartUseCase {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartPort cartPort;
    private final ProductCatalogPort productCatalogPort;
    private final Clock clock;

    CartService(CartPort cartPort, ProductCatalogPort productCatalogPort, Clock clock) {
        this.cartPort = cartPort;
        this.productCatalogPort = productCatalogPort;
        this.clock = clock;
    }

    @Override
    public CartResult get(long userId) {
        return view(cartPort.findByUserId(UserId.of(userId)));
    }

    @Override
    public CartResult put(long userId, UUID productId, int quantity) {
        UserId user = UserId.of(userId);
        ProductId product = ProductId.of(productId);
        Quantity qty = Quantity.of(quantity);
        CatalogProduct current = productCatalogPort.findByIds(List.of(productId)).stream()
                .filter(p -> p.productId().equals(productId) && p.onSale())
                .findFirst()
                .orElseThrow(() -> new ProductNotOrderableException(List.of(productId)));
        Cart cart = Cart.of(user, cartPort.findByUserId(user));
        Instant now = clock.instant();
        CartItem item = cart.put(product, qty, Money.of(current.price()), now);
        cartPort.upsert(user, item, now);
        return view(cart.items());
    }

    @Override
    public CartResult remove(long userId, UUID productId) {
        UserId user = UserId.of(userId);
        cartPort.delete(user, ProductId.of(productId));
        return get(userId);
    }

    private CartResult view(List<CartItem> items) {
        if (items.isEmpty()) {
            return new CartResult(List.of(), true);
        }
        Map<UUID, CatalogProduct> current;
        try {
            current = productCatalogPort.findByIds(items.stream().map(i -> i.productId().value()).toList()).stream()
                    .collect(Collectors.toMap(CatalogProduct::productId, Function.identity(), (a, b) -> a));
        } catch (ProductCatalogUnavailableException e) {
            // 장바구니는 담을 때 가격으로라도 보여 준다 (조회 fallback, ADR 0016)
            log.warn("장바구니 지금 가격을 읽지 못함, 담을 때 가격으로 보여 줌: {}", e.getCause() == null ? e : e.getCause().toString());
            return new CartResult(items.stream()
                    .map(i -> new CartResult.Item(i.productId().value(), null, i.quantity().value(),
                            i.addedPrice().value(), null, null))
                    .toList(), false);
        }
        return new CartResult(items.stream().map(i -> {
            CatalogProduct p = current.get(i.productId().value());
            return p == null
                    ? new CartResult.Item(i.productId().value(), null, i.quantity().value(), i.addedPrice().value(),
                    null, false)
                    : new CartResult.Item(i.productId().value(), p.name(), i.quantity().value(),
                    i.addedPrice().value(), p.price(), p.onSale());
        }).toList(), true);
    }
}
