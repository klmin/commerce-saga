package com.klmin.commerce.inventoryservice.stock.application.service;

import com.klmin.commerce.inventoryservice.stock.application.StockNotFoundException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.out.SendStockReplyPort;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockPort;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockReservationPort;
import com.klmin.commerce.inventoryservice.stock.domain.ReservationStatus;
import com.klmin.commerce.inventoryservice.stock.domain.Stock;
import com.klmin.commerce.inventoryservice.stock.domain.StockReservation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");

    private final InMemoryStocks stocks = new InMemoryStocks();
    private final InMemoryReservations reservations = new InMemoryReservations();
    private final RecordingReplies replies = new RecordingReplies();
    private final StockService service =
            new StockService(stocks, reservations, replies, Clock.fixed(NOW, ZoneOffset.UTC));

    private static ReserveStockCommand command(UUID orderId, ReserveStockCommand.Line... lines) {
        return new ReserveStockCommand(orderId, List.of(lines));
    }

    private static ReserveStockCommand.Line line(long productId, int quantity) {
        return new ReserveStockCommand.Line(productId, quantity);
    }

    @Test
    void reserve_allLines_reservesAndRepliesReserved() {
        stocks.put(1L, 10);
        stocks.put(2L, 5);
        UUID orderId = UUID.randomUUID();

        service.reserve(command(orderId, line(1L, 3), line(2L, 5)));

        assertThat(stocks.available(1L)).isEqualTo(7);
        assertThat(stocks.reserved(1L)).isEqualTo(3);
        assertThat(stocks.available(2L)).isZero();
        assertThat(reservations.store.get(orderId).status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(replies.log).containsExactly("reserved:" + orderId);
    }

    @Test
    void reserve_insufficientStock_reservesNothingAndRepliesFailed() {
        stocks.put(1L, 10);
        stocks.put(2L, 1);
        UUID orderId = UUID.randomUUID();

        service.reserve(command(orderId, line(1L, 3), line(2L, 2)));

        // 앞 항목도 잡지 않는다 (전부 아니면 전무)
        assertThat(stocks.available(1L)).isEqualTo(10);
        assertThat(stocks.available(2L)).isEqualTo(1);
        assertThat(reservations.store.get(orderId).status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(replies.log).containsExactly("failed:" + orderId + ":insufficient stock: productId=2");
    }

    @Test
    void reserve_unknownProduct_repliesFailed() {
        stocks.put(1L, 10);
        UUID orderId = UUID.randomUUID();

        service.reserve(command(orderId, line(1L, 1), line(99L, 1)));

        assertThat(stocks.available(1L)).isEqualTo(10);
        assertThat(replies.log).containsExactly("failed:" + orderId + ":unknown product: productId=99");
    }

    @Test
    void reserve_invalidCommand_repliesFailedWithoutTouchingStock() {
        stocks.put(1L, 10);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        service.reserve(command(a));
        service.reserve(command(b, line(1L, 0)));
        service.reserve(command(c, line(1L, 1), line(1L, 1)));

        assertThat(stocks.available(1L)).isEqualTo(10);
        assertThat(stocks.lockCalls).isZero();
        assertThat(replies.log).hasSize(3).allMatch(s -> s.startsWith("failed:") && s.contains("invalid command"));
    }

    @Test
    void duplicateCommand_doesNotReserveTwice_andRepliesSameResult() {
        stocks.put(1L, 10);
        UUID orderId = UUID.randomUUID();

        service.reserve(command(orderId, line(1L, 3)));
        service.reserve(command(orderId, line(1L, 3)));

        assertThat(stocks.available(1L)).isEqualTo(7);
        assertThat(stocks.reserved(1L)).isEqualTo(3);
        assertThat(replies.log).containsExactly("reserved:" + orderId, "reserved:" + orderId);
    }

    @Test
    void duplicateCommand_afterRejection_repliesFailedAgain_evenIfStockRefilled() {
        stocks.put(1L, 1);
        UUID orderId = UUID.randomUUID();
        service.reserve(command(orderId, line(1L, 2)));

        stocks.put(1L, 100);
        service.reserve(command(orderId, line(1L, 2)));

        assertThat(stocks.available(1L)).isEqualTo(100);
        assertThat(replies.log).hasSize(2).allMatch(s -> s.startsWith("failed:"));
    }

    @Test
    void getStock() {
        stocks.put(1L, 10);

        assertThat(service.getStock(1L).available()).isEqualTo(10);
        assertThatThrownBy(() -> service.getStock(2L)).isInstanceOf(StockNotFoundException.class);
    }

    static class InMemoryStocks implements StockPort {

        final Map<Long, Stock> store = new TreeMap<>();
        int lockCalls;

        void put(long productId, int available) {
            store.put(productId, Stock.restore(productId, available, 0, NOW));
        }

        int available(long productId) {
            return store.get(productId).available();
        }

        int reserved(long productId) {
            return store.get(productId).reserved();
        }

        @Override
        public Optional<Stock> findById(long productId) {
            return Optional.ofNullable(store.get(productId)).map(InMemoryStocks::copy);
        }

        @Override
        public List<Stock> findAllForUpdate(Collection<Long> productIds) {
            lockCalls++;
            return productIds.stream().sorted().filter(store::containsKey).map(id -> copy(store.get(id))).toList();
        }

        @Override
        public void update(Collection<Stock> stocks) {
            stocks.forEach(s -> store.put(s.productId(), copy(s)));
        }

        private static Stock copy(Stock s) {
            return Stock.restore(s.productId(), s.available(), s.reserved(), s.updatedAt());
        }
    }

    static class InMemoryReservations implements StockReservationPort {

        final Map<UUID, StockReservation> store = new HashMap<>();

        @Override
        public Optional<StockReservation> findByOrderId(UUID orderId) {
            return Optional.ofNullable(store.get(orderId));
        }

        @Override
        public void insert(StockReservation reservation) {
            if (store.putIfAbsent(reservation.orderId(), reservation) != null) {
                throw new IllegalStateException("duplicate reservation");
            }
        }
    }

    static class RecordingReplies implements SendStockReplyPort {

        final List<String> log = new ArrayList<>();

        @Override
        public void stockReserved(UUID orderId) {
            log.add("reserved:" + orderId);
        }

        @Override
        public void stockReservationFailed(UUID orderId, String reason) {
            log.add("failed:" + orderId + ":" + reason);
        }
    }
}
