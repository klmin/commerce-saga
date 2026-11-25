package com.klmin.commerce.orderservice;

import com.klmin.commerce.orderservice.order.application.IdempotencyKeyReusedException;
import com.klmin.commerce.orderservice.order.application.port.in.ListMyOrdersQuery;
import com.klmin.commerce.orderservice.order.application.port.in.ListMyOrdersUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.OrderSummaryResult;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderResult;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문 Idempotency-Key (시나리오 10) 와 내 주문 목록을 실제 Postgres 로 확인한다.
 * 컨텍스트를 테스트끼리 공유하므로 테스트마다 다른 사용자 id 를 쓴다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StubProductCatalogConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class OrderQueryAndIdempotencyIntegrationTest {

    @Autowired
    PlaceOrderUseCase placeOrderUseCase;

    @Autowired
    ListMyOrdersUseCase listMyOrdersUseCase;

    @Autowired
    JdbcClient jdbc;

    private static PlaceOrderCommand command(long userId, int quantity, String key) {
        return new PlaceOrderCommand(userId, List.of(new PlaceOrderCommand.Line(10L, quantity, 1_000L)), 0L, key);
    }

    private long count(String table, long userId) {
        return jdbc.sql("select count(*) from " + table + " where user_id = ?").param(userId).query(Long.class).single();
    }

    @Test
    void sameKeyAtTheSameTime_createsOneOrder_andEveryoneGetsIt() throws Exception {
        long userId = 41L;
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PlaceOrderResult>> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            results = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return placeOrderUseCase.placeOrder(command(userId, 1, "double-click"));
                    }))
                    .toList();
            start.countDown();
        }

        List<PlaceOrderResult> done = results.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }).toList();
        assertThat(done).filteredOn(PlaceOrderResult::created).hasSize(1);
        assertThat(done).extracting(r -> r.order().id()).containsOnly(done.getFirst().order().id());
        assertThat(count("orders", userId)).isEqualTo(1);
        assertThat(count("order_sagas", userId)).isEqualTo(1);
        assertThat(count("order_idempotency_keys", userId)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBody_isRejected_andRollsBackNothingOfTheFirst() {
        long userId = 42L;
        PlaceOrderResult first = placeOrderUseCase.placeOrder(command(userId, 1, "k"));

        assertThatThrownBy(() -> placeOrderUseCase.placeOrder(command(userId, 2, "k")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(count("orders", userId)).isEqualTo(1);
        assertThat(placeOrderUseCase.placeOrder(command(userId, 1, "k")).order().id()).isEqualTo(first.order().id());
    }

    @Test
    void myOrders_newestFirst_withCursor_onlyMine() {
        long userId = 43L;
        UUID first = placeOrderUseCase.placeOrder(command(userId, 1, null)).order().id();
        UUID second = placeOrderUseCase.placeOrder(command(userId, 2, null)).order().id();
        UUID third = placeOrderUseCase.placeOrder(command(userId, 3, null)).order().id();
        placeOrderUseCase.placeOrder(command(44L, 1, null));

        List<OrderSummaryResult> page1 = listMyOrdersUseCase.listMyOrders(new ListMyOrdersQuery(userId, null, 2));
        List<OrderSummaryResult> page2 = listMyOrdersUseCase.listMyOrders(
                new ListMyOrdersQuery(userId, page1.getLast().orderId(), 2));

        assertThat(page1).extracting(OrderSummaryResult::orderId).containsExactly(third, second);
        assertThat(page2).extracting(OrderSummaryResult::orderId).containsExactly(first);
        assertThat(page1.getFirst().lines()).containsExactly(new OrderSummaryResult.Line(10L, 3, 1_000L));
        assertThat(page1.getFirst().totalAmount()).isEqualTo(3_000L);
        assertThat(page1.getFirst().status()).isNotBlank();
        assertThat(listMyOrdersUseCase.listMyOrders(new ListMyOrdersQuery(45L, null, 20))).isEmpty();
    }
}
