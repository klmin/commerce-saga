package com.klmin.commerce.inventoryservice;

import com.klmin.commerce.inventoryservice.common.error.ConflictException;
import com.klmin.commerce.inventoryservice.stock.application.IdempotencyKeyReusedException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockResult;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.GetStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 재고 조정 (ADR 0019): 실제 Postgres 에서 행 만들기·행 락·멱등 키 유니크가 맞물리는지 본다.
 * 상품마다 새 UUID 를 써서 시드 재고(...0001~0005)와 다른 테스트에 영향을 주지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AdminStockIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("01900000-0000-7000-8001-000000000100");

    @Autowired
    AdjustStockUseCase adjustStockUseCase;

    @Autowired
    ReserveStockUseCase reserveStockUseCase;

    @Autowired
    GetStockUseCase getStockUseCase;

    @Autowired
    JdbcTemplate jdbc;

    private static AdjustStockCommand command(UUID productId, int delta, String key) {
        return new AdjustStockCommand(productId, delta, null, ADMIN, key);
    }

    private int adjustmentRows(UUID productId) {
        return jdbc.queryForObject("select count(*) from stock_adjustments where product_id = ?", Integer.class,
                productId);
    }

    @Test
    void newProduct_firstReceipt_createsStock_thenOrderCanReserve() {
        UUID productId = UUID.randomUUID();

        AdjustStockResult result = adjustStockUseCase.adjust(command(productId, 5, UUID.randomUUID().toString()));

        assertThat(result.available()).isEqualTo(5);
        assertThat(getStockUseCase.getStock(productId).available()).isEqualTo(5);
        assertThat(adjustmentRows(productId)).isEqualTo(1);

        // 입고한 상품은 주문 예약이 된다 (이 기능이 없을 때는 재고 행이 없어 늘 예약 실패)
        reserveStockUseCase.reserve(UUID.randomUUID(),
                new ReserveStockCommand(UUID.randomUUID(), List.of(new ReserveStockCommand.Line(productId, 2))));
        assertThat(getStockUseCase.getStock(productId).available()).isEqualTo(3);
        assertThat(getStockUseCase.getStock(productId).reserved()).isEqualTo(2);
    }

    @Test
    void sameKey_replaysOnce_andDifferentContentIs422() {
        UUID productId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        adjustStockUseCase.adjust(command(productId, 10, key));

        AdjustStockResult replay = adjustStockUseCase.adjust(command(productId, 10, key));

        assertThat(replay.replayed()).isTrue();
        assertThat(getStockUseCase.getStock(productId).available()).isEqualTo(10);
        assertThatThrownBy(() -> adjustStockUseCase.adjust(command(productId, 9, key)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(adjustmentRows(productId)).isEqualTo(1);
    }

    @Test
    void overDeduction_isConflict_andRollsBack() {
        UUID productId = UUID.randomUUID();
        adjustStockUseCase.adjust(command(productId, 3, UUID.randomUUID().toString()));

        assertThatThrownBy(() -> adjustStockUseCase.adjust(command(productId, -4, UUID.randomUUID().toString())))
                .isInstanceOf(ConflictException.class);

        assertThat(getStockUseCase.getStock(productId).available()).isEqualTo(3);
        assertThat(adjustmentRows(productId)).isEqualTo(1);
    }

    @Test
    void negativeOnMissingStock_isConflict_andLeavesNoRow() {
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> adjustStockUseCase.adjust(command(productId, -1, UUID.randomUUID().toString())))
                .isInstanceOf(ConflictException.class);

        assertThat(jdbc.queryForObject("select count(*) from stocks where product_id = ?", Integer.class, productId))
                .isZero();
    }

    // 새 상품에 여러 관리자 요청이 동시에 와도(행 만들기 경합 포함) 모두 한 번씩 더해진다
    @Test
    void concurrentAdjustments_withDifferentKeys_allApply() throws Exception {
        UUID productId = UUID.randomUUID();
        int n = 20;

        runConcurrently(n, i -> adjustStockUseCase.adjust(command(productId, 1, "c-" + productId + "-" + i)));

        assertThat(getStockUseCase.getStock(productId).available()).isEqualTo(n);
        assertThat(adjustmentRows(productId)).isEqualTo(n);
    }

    // 같은 키로 동시에(더블 클릭·재시도) 와도 한 번만 반영된다: 뒤 요청은 행 락을 기다린 뒤 앞의 기록을 본다
    @Test
    void concurrentSameKey_appliesOnce() throws Exception {
        UUID productId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        List<AdjustStockResult> results = runConcurrently(8, i -> adjustStockUseCase.adjust(command(productId, 7, key)));

        assertThat(getStockUseCase.getStock(productId).available()).isEqualTo(7);
        assertThat(adjustmentRows(productId)).isEqualTo(1);
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
    }

    // 입고와 주문 예약이 같은 행을 잡아도 서로의 변경을 지우지 않는다 (수량을 덮어쓰지 않고 더하므로)
    @Test
    void adjustmentsAndReservations_interleave_withoutLostUpdate() throws Exception {
        UUID productId = UUID.randomUUID();
        adjustStockUseCase.adjust(command(productId, 100, UUID.randomUUID().toString()));
        int n = 10;

        runConcurrently(n * 2, i -> {
            if (i % 2 == 0) {
                return adjustStockUseCase.adjust(command(productId, 5, "mix-" + productId + "-" + i));
            }
            reserveStockUseCase.reserve(UUID.randomUUID(), new ReserveStockCommand(UUID.randomUUID(),
                    List.of(new ReserveStockCommand.Line(productId, 3))));
            return null;
        });

        assertThat(getStockUseCase.getStock(productId).available()).isEqualTo(100 + n * 5 - n * 3);
        assertThat(getStockUseCase.getStock(productId).reserved()).isEqualTo(n * 3);
    }

    private interface Task<T> {
        T run(int i) throws Exception;
    }

    private static <T> List<T> runConcurrently(int n, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return task.run(index);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            start.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdownNow();
        }
    }
}
