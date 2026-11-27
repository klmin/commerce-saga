package com.klmin.commerce.orderservice;

import com.klmin.commerce.orderservice.order.application.port.in.CartResult;
import com.klmin.commerce.orderservice.order.application.port.in.CartUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static org.assertj.core.api.Assertions.assertThat;

/** cart_items 저장(upsert)과 장바구니 주문의 줄 삭제가 주문 트랜잭션과 함께 커밋되는지 (Postgres) */
@SpringBootTest(properties = "management.logging.export.enabled=false")
@Import({TestcontainersConfiguration.class, StubProductCatalogConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class CartIntegrationTest {

    @Autowired
    CartUseCase cart;

    @Autowired
    PlaceOrderUseCase placeOrder;

    @Test
    void putTwice_keepsSeenPrice_andOrderFromCartRemovesOrderedLines() {
        long user = 70_001L;
        cart.put(user, pid(10), 1);
        cart.put(user, pid(10), 3);
        cart.put(user, pid(11), 2);

        CartResult before = cart.get(user);
        assertThat(before.items()).extracting(CartResult.Item::productId).containsExactly(pid(10), pid(11));
        assertThat(before.items().getFirst().quantity()).isEqualTo(3);
        assertThat(before.items().getFirst().addedPrice()).isEqualTo(StubProductCatalogConfiguration.PRICE);

        placeOrder.placeOrder(new PlaceOrderCommand(user,
                List.of(new PlaceOrderCommand.Line(pid(10), 3, StubProductCatalogConfiguration.PRICE)), 0L, null, true));

        assertThat(cart.get(user).items()).extracting(CartResult.Item::productId).containsExactly(pid(11));
        // 다른 사용자의 장바구니는 그대로
        assertThat(cart.get(70_002L).items()).isEmpty();
    }
}
