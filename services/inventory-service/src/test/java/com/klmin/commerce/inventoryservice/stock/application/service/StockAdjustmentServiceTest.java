package com.klmin.commerce.inventoryservice.stock.application.service;

import com.klmin.commerce.inventoryservice.common.error.ConflictException;
import com.klmin.commerce.inventoryservice.common.error.DomainValidationException;
import com.klmin.commerce.inventoryservice.stock.application.IdempotencyKeyReusedException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockResult;
import com.klmin.commerce.inventoryservice.stock.application.port.in.StockAdjustmentPageResult;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockAdjustmentPort;
import com.klmin.commerce.inventoryservice.stock.domain.ProductId;
import com.klmin.commerce.inventoryservice.stock.domain.StockAdjustment;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockAdjustmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00Z");
    private static final UUID P1 = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
    private static final UUID P2 = UUID.fromString("01900000-0000-7000-8000-0000000000a2");
    private static final UUID ADMIN = UUID.fromString("01900000-0000-7000-8001-000000000100");
    private static final UUID OTHER_ADMIN = UUID.fromString("01900000-0000-7000-8001-000000000101");

    private final StockServiceTest.InMemoryStocks stocks = new StockServiceTest.InMemoryStocks();
    private final InMemoryAdjustments adjustments = new InMemoryAdjustments();
    private final StockAdjustmentService service =
            new StockAdjustmentService(stocks, adjustments, Clock.fixed(NOW, ZoneOffset.UTC));

    private static AdjustStockCommand command(UUID productId, int delta, String reason, String key) {
        return new AdjustStockCommand(productId, delta, reason, ADMIN, key);
    }

    @Test
    void adjust_newProduct_createsStockRow_andRecords() {
        AdjustStockResult result = service.adjust(command(P1, 10, " 첫 입고 ", "k1"));

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(result.available()).isEqualTo(10);
        assertThat(result.reserved()).isZero();
        assertThat(result.replayed()).isFalse();
        assertThat(result.adjustment().delta()).isEqualTo(10);
        assertThat(result.adjustment().availableAfter()).isEqualTo(10);
        assertThat(result.adjustment().reason()).isEqualTo("첫 입고");
        assertThat(result.adjustment().adjustedBy()).isEqualTo(ADMIN);
        assertThat(result.adjustment().adjustedAt()).isEqualTo(NOW);
        assertThat(adjustments.rows).hasSize(1);
    }

    @Test
    void adjust_existingStock_keepsReserved() {
        stocks.store.put(ProductId.of(P1), com.klmin.commerce.inventoryservice.stock.domain.Stock.restore(
                ProductId.of(P1), 5, 3, NOW));

        AdjustStockResult result = service.adjust(command(P1, -2, null, "k1"));

        assertThat(result.available()).isEqualTo(3);
        assertThat(result.reserved()).isEqualTo(3);
        assertThat(stocks.reserved(P1)).isEqualTo(3);
    }

    @Test
    void adjust_sameKeySameRequest_replaysWithoutApplyingAgain() {
        service.adjust(command(P1, 10, "입고", "k1"));

        AdjustStockResult replay = service.adjust(command(P1, 10, "입고", "k1"));

        assertThat(replay.replayed()).isTrue();
        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(replay.adjustment().availableAfter()).isEqualTo(10);
        assertThat(adjustments.rows).hasSize(1);
    }

    @Test
    void adjust_sameKeyDifferentRequest_is422_andKeepsStock() {
        service.adjust(command(P1, 10, "입고", "k1"));

        assertThatThrownBy(() -> service.adjust(command(P1, 11, "입고", "k1")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> service.adjust(command(P2, 10, "입고", "k1")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> service.adjust(command(P1, 10, "다른 사유", "k1")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(adjustments.rows).hasSize(1);
    }

    @Test
    void adjust_sameKeyByAnotherAdmin_isSeparate() {
        service.adjust(command(P1, 10, null, "k1"));

        service.adjust(new AdjustStockCommand(P1, 10, null, OTHER_ADMIN, "k1"));

        assertThat(stocks.available(P1)).isEqualTo(20);
    }

    @Test
    void adjust_moreThanAvailable_isConflict_andRecordsNothing() {
        service.adjust(command(P1, 3, null, "k1"));

        assertThatThrownBy(() -> service.adjust(command(P1, -4, null, "k2"))).isInstanceOf(ConflictException.class);
        assertThat(stocks.available(P1)).isEqualTo(3);
        assertThat(adjustments.rows).hasSize(1);
    }

    @Test
    void command_rejectsZeroOutOfRangeLongReasonAndBadKey() {
        assertThatThrownBy(() -> command(P1, 0, null, "k")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> command(P1, AdjustStockCommand.MAX_DELTA + 1, null, "k"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> command(P1, Integer.MIN_VALUE, null, "k"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> command(P1, 1, "가".repeat(201), "k")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> command(P1, 1, null, "")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> command(P1, 1, null, "has space")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> command(P1, 1, null, null)).isInstanceOf(DomainValidationException.class);
        assertThat(command(P1, 1, "   ", "k").reason()).isNull();
    }

    @Test
    void adjustments_recentFirst_withPaging() {
        service.adjust(command(P1, 10, null, "k1"));
        service.adjust(command(P1, -3, null, "k2"));
        service.adjust(command(P1, 5, null, "k3"));
        service.adjust(command(P2, 1, null, "k4"));

        StockAdjustmentPageResult first = service.adjustments(P1, 0, 2);
        StockAdjustmentPageResult second = service.adjustments(P1, 1, 2);

        assertThat(first.items()).extracting(AdjustStockResult.Adjustment::delta).containsExactly(5, -3);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.items()).extracting(AdjustStockResult.Adjustment::availableAfter).containsExactly(10);
        assertThat(second.hasNext()).isFalse();
        assertThat(service.adjustments(UUID.randomUUID(), 0, 20).items()).isEmpty();
    }

    @Test
    void adjustments_outOfRangePaging_isRejected() {
        assertThatThrownBy(() -> service.adjustments(P1, -1, 20)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> service.adjustments(P1, Integer.MAX_VALUE, 100))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> service.adjustments(P1, 0, 0)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> service.adjustments(P1, 0, 101)).isInstanceOf(DomainValidationException.class);
    }

    static class InMemoryAdjustments implements StockAdjustmentPort {

        final List<StockAdjustment> rows = new ArrayList<>();

        @Override
        public Optional<StockAdjustment> findByKey(UUID adjustedBy, String idempotencyKey) {
            return rows.stream()
                    .filter(a -> a.adjustedBy().equals(adjustedBy) && a.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public Optional<StockAdjustment> insert(StockAdjustment a) {
            if (findByKey(a.adjustedBy(), a.idempotencyKey()).isPresent()) {
                return Optional.empty();
            }
            StockAdjustment saved = new StockAdjustment((long) rows.size() + 1, a.productId(), a.delta(),
                    a.availableAfter(), a.reason(), a.adjustedBy(), a.idempotencyKey(), a.adjustedAt());
            rows.add(saved);
            return Optional.of(saved);
        }

        @Override
        public List<StockAdjustment> findByProduct(ProductId productId, int offset, int limit) {
            return rows.stream().filter(a -> a.productId().equals(productId))
                    .sorted(Comparator.comparing(StockAdjustment::id).reversed())
                    .skip(offset).limit(limit).toList();
        }
    }
}
