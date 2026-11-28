package com.klmin.commerce.inventoryservice.stock.application.service;

import com.klmin.commerce.inventoryservice.stock.application.StockNotFoundException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.out.SendStockReplyPort;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockPort;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockReservationPort;
import com.klmin.commerce.inventoryservice.stock.domain.ProductId;
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
    private static final UUID P1 = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
    private static final UUID P2 = UUID.fromString("01900000-0000-7000-8000-0000000000a2");
    private static final UUID UNKNOWN = UUID.fromString("01900000-0000-7000-8000-0000000000ff");

    private final InMemoryStocks stocks = new InMemoryStocks();
    private final InMemoryReservations reservations = new InMemoryReservations();
    private final RecordingReplies replies = new RecordingReplies();
    private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
    private final StockService service =
            new StockService(stocks, reservations, replies, processed, Clock.fixed(NOW, ZoneOffset.UTC));

    private static ReserveStockCommand command(UUID orderId, ReserveStockCommand.Line... lines) {
        return new ReserveStockCommand(orderId, List.of(lines));
    }

    private static ReserveStockCommand.Line line(UUID productId, int quantity) {
        return new ReserveStockCommand.Line(productId, quantity);
    }

    @Test
    void reserve_allLines_reservesAndRepliesReserved() {
        stocks.put(P1, 10);
        stocks.put(P2, 5);
        UUID orderId = UUID.randomUUID();

        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3), line(P2, 5)));

        assertThat(stocks.available(P1)).isEqualTo(7);
        assertThat(stocks.reserved(P1)).isEqualTo(3);
        assertThat(stocks.available(P2)).isZero();
        assertThat(reservations.store.get(orderId).status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(replies.log).containsExactly("reserved:" + orderId);
    }

    @Test
    void reserve_insufficientStock_reservesNothingAndRepliesFailed() {
        stocks.put(P1, 10);
        stocks.put(P2, 1);
        UUID orderId = UUID.randomUUID();

        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3), line(P2, 2)));

        // 앞 항목도 잡지 않는다 (전부 아니면 전무)
        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(stocks.available(P2)).isEqualTo(1);
        assertThat(reservations.store.get(orderId).status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(replies.log).containsExactly("failed:" + orderId + ":insufficient stock: productId=" + P2);
    }

    @Test
    void reserve_unknownProduct_repliesFailed() {
        stocks.put(P1, 10);
        UUID orderId = UUID.randomUUID();

        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 1), line(UNKNOWN, 1)));

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(replies.log).containsExactly("failed:" + orderId + ":unknown product: productId=" + UNKNOWN);
    }

    @Test
    void reserve_invalidCommand_repliesFailedWithoutTouchingStock() {
        stocks.put(P1, 10);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        service.reserve(UUID.randomUUID(), command(a));
        service.reserve(UUID.randomUUID(), command(b, line(P1, 0)));
        service.reserve(UUID.randomUUID(), command(c, line(P1, 1), line(P1, 1)));
        service.reserve(UUID.randomUUID(), command(UUID.randomUUID(), line(null, 1)));

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(stocks.lockCalls).isZero();
        assertThat(replies.log).hasSize(4).allMatch(s -> s.startsWith("failed:") && s.contains("invalid command"));
    }

    @Test
    void duplicateCommand_doesNotReserveTwice_andRepliesSameResult() {
        stocks.put(P1, 10);
        UUID orderId = UUID.randomUUID();

        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));

        assertThat(stocks.available(P1)).isEqualTo(7);
        assertThat(stocks.reserved(P1)).isEqualTo(3);
        assertThat(replies.log).containsExactly("reserved:" + orderId, "reserved:" + orderId);
    }

    @Test
    void redeliveredMessage_isSkipped_withoutChangingStockOrReplying() {
        stocks.put(P1, 10);
        UUID orderId = UUID.randomUUID();
        UUID reserveId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();

        service.reserve(reserveId, command(orderId, line(P1, 3)));
        service.reserve(reserveId, command(orderId, line(P1, 3)));
        service.release(releaseId, orderId);
        service.release(releaseId, orderId);
        service.reserve(reserveId, command(orderId, line(P1, 3)));   // 보상 뒤 늦게 다시 온 원래 메시지

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(stocks.reserved(P1)).isZero();
        assertThat(replies.log).containsExactly("reserved:" + orderId, "released:" + orderId);
        assertThat(processed.ids).containsExactlyInAnyOrder(reserveId, releaseId);
    }

    @Test
    void redeliveredOrderCompleted_confirmsOnce() {
        stocks.put(P1, 10);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));

        service.confirm(eventId, orderId);
        service.confirm(eventId, orderId);

        assertThat(stocks.available(P1)).isEqualTo(7);
        assertThat(stocks.reserved(P1)).isZero();
        assertThat(reservations.store.get(orderId).status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void duplicateCommand_afterRejection_repliesFailedAgain_evenIfStockRefilled() {
        stocks.put(P1, 1);
        UUID orderId = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 2)));

        stocks.put(P1, 100);
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 2)));

        assertThat(stocks.available(P1)).isEqualTo(100);
        assertThat(replies.log).hasSize(2).allMatch(s -> s.startsWith("failed:"));
    }

    @Test
    void release_returnsReservedStock_andRepliesReleased() {
        stocks.put(P1, 10);
        stocks.put(P2, 5);
        UUID orderId = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3), line(P2, 2)));

        service.release(UUID.randomUUID(), orderId);

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(stocks.reserved(P1)).isZero();
        assertThat(stocks.available(P2)).isEqualTo(5);
        assertThat(reservations.store.get(orderId).status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(replies.log).containsExactly("reserved:" + orderId, "released:" + orderId);
    }

    @Test
    void duplicateRelease_returnsStockOnce_butRepliesEveryTime() {
        stocks.put(P1, 10);
        UUID orderId = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));

        service.release(UUID.randomUUID(), orderId);
        service.release(UUID.randomUUID(), orderId);

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(replies.log).containsExactly("reserved:" + orderId, "released:" + orderId, "released:" + orderId);
    }

    @Test
    void release_ofRejectedReservation_changesNothing_andRepliesReleased() {
        stocks.put(P1, 1);
        UUID orderId = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 5)));

        service.release(UUID.randomUUID(), orderId);

        assertThat(stocks.available(P1)).isEqualTo(1);
        assertThat(reservations.store.get(orderId).status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(replies.log).last().isEqualTo("released:" + orderId);
    }

    @Test
    void release_withoutReservation_blocksLateReserve() {
        stocks.put(P1, 10);
        UUID orderId = UUID.randomUUID();

        service.release(UUID.randomUUID(), orderId);
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(replies.log).containsExactly("released:" + orderId,
                "failed:" + orderId + ":reservation already released");
    }

    @Test
    void confirm_movesReservedToSold_once() {
        stocks.put(P1, 10);
        UUID orderId = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));

        service.confirm(UUID.randomUUID(), orderId);
        service.confirm(UUID.randomUUID(), orderId);

        assertThat(stocks.available(P1)).isEqualTo(7);
        assertThat(stocks.reserved(P1)).isZero();
        assertThat(reservations.store.get(orderId).status()).isEqualTo(ReservationStatus.CONFIRMED);
        // 확정 후 재전송된 ReserveStock 에는 성공으로 응답 (재고는 그대로)
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));
        assertThat(stocks.available(P1)).isEqualTo(7);
        assertThat(replies.log).last().isEqualTo("reserved:" + orderId);
    }

    @Test
    void confirmAndReleaseAreExclusive() {
        stocks.put(P1, 10);
        UUID confirmed = UUID.randomUUID();
        UUID released = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(confirmed, line(P1, 2)));
        service.reserve(UUID.randomUUID(), command(released, line(P1, 3)));

        service.confirm(UUID.randomUUID(), confirmed);
        service.release(UUID.randomUUID(), confirmed);   // 완료된 주문에 늦은 보상: 재고 그대로
        service.release(UUID.randomUUID(), released);
        service.confirm(UUID.randomUUID(), released);    // 보상된 주문에 늦은 완료: 재고 그대로

        assertThat(stocks.available(P1)).isEqualTo(8);
        assertThat(stocks.reserved(P1)).isZero();
    }

    // 완료 후 취소 (ADR 0020)
    @Test
    void returnStock_afterConfirm_restocksSoldQuantity_once() {
        stocks.put(P1, 10);
        stocks.put(P2, 5);
        UUID orderId = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3), line(P2, 2)));
        service.confirm(UUID.randomUUID(), orderId);

        service.returnStock(UUID.randomUUID(), orderId);
        service.returnStock(UUID.randomUUID(), orderId);

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(stocks.reserved(P1)).isZero();
        assertThat(stocks.available(P2)).isEqualTo(5);
        assertThat(reservations.store.get(orderId).status()).isEqualTo(ReservationStatus.RETURNED);
        assertThat(replies.log).containsExactly("reserved:" + orderId, "returned:" + orderId, "returned:" + orderId);
    }

    // OrderCompleted 보다 ReturnStock 이 먼저 처리되면 예약을 풀고, 뒤늦은 확정은 아무것도 하지 않는다
    @Test
    void returnStock_beforeConfirm_releasesReservation_andLateConfirmIsNoop() {
        stocks.put(P1, 10);
        UUID orderId = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));

        service.returnStock(UUID.randomUUID(), orderId);
        service.confirm(UUID.randomUUID(), orderId);

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(stocks.reserved(P1)).isZero();
        assertThat(reservations.store.get(orderId).status()).isEqualTo(ReservationStatus.RETURNED);
    }

    @Test
    void returnStock_ofReleasedRejectedOrMissing_changesNothing_butReplies() {
        stocks.put(P1, 10);
        UUID released = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(released, line(P1, 3)));
        service.release(UUID.randomUUID(), released);
        service.reserve(UUID.randomUUID(), command(rejected, line(P1, 50)));

        service.returnStock(UUID.randomUUID(), released);
        service.returnStock(UUID.randomUUID(), rejected);
        service.returnStock(UUID.randomUUID(), missing);

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(reservations.store.get(released).status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservations.store.get(rejected).status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(reservations.store).doesNotContainKey(missing);
        assertThat(replies.log).endsWith("returned:" + released, "returned:" + rejected, "returned:" + missing);
    }

    @Test
    void returnStock_sameMessageTwice_isProcessedOnce() {
        stocks.put(P1, 10);
        UUID orderId = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));
        service.confirm(UUID.randomUUID(), orderId);
        UUID messageId = UUID.randomUUID();

        service.returnStock(messageId, orderId);
        service.returnStock(messageId, orderId);

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(replies.log).filteredOn(s -> s.startsWith("returned:")).hasSize(1);
    }

    // 반환된 주문에 늦게 온 ReserveStock 은 재고를 잡지 않고 실패로 응답한다
    @Test
    void lateReserve_afterReturn_isFailure() {
        stocks.put(P1, 10);
        UUID orderId = UUID.randomUUID();
        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));
        service.confirm(UUID.randomUUID(), orderId);
        service.returnStock(UUID.randomUUID(), orderId);

        service.reserve(UUID.randomUUID(), command(orderId, line(P1, 3)));

        assertThat(stocks.available(P1)).isEqualTo(10);
        assertThat(replies.log).last().isEqualTo("failed:" + orderId + ":reservation already released");
    }

    @Test
    void confirm_withoutReservation_isIgnored() {
        service.confirm(UUID.randomUUID(), UUID.randomUUID());

        assertThat(replies.log).isEmpty();
    }

    @Test
    void getStock() {
        stocks.put(P1, 10);

        assertThat(service.getStock(P1).available()).isEqualTo(10);
        assertThatThrownBy(() -> service.getStock(P2)).isInstanceOf(StockNotFoundException.class);
    }

    static class InMemoryStocks implements StockPort {

        final Map<ProductId, Stock> store = new TreeMap<>();
        int lockCalls;

        void put(UUID productId, int available) {
            store.put(ProductId.of(productId), Stock.restore(ProductId.of(productId), available, 0, NOW));
        }

        long available(UUID productId) {
            return store.get(ProductId.of(productId)).available();
        }

        long reserved(UUID productId) {
            return store.get(ProductId.of(productId)).reserved();
        }

        @Override
        public Optional<Stock> findById(ProductId productId) {
            return Optional.ofNullable(store.get(productId)).map(InMemoryStocks::copy);
        }

        @Override
        public List<Stock> findAllForUpdate(Collection<ProductId> productIds) {
            lockCalls++;
            return productIds.stream().sorted().filter(store::containsKey).map(id -> copy(store.get(id))).toList();
        }

        @Override
        public void update(Collection<Stock> stocks) {
            stocks.forEach(s -> store.put(s.productId(), copy(s)));
        }

        @Override
        public void createIfAbsent(ProductId productId, Instant now) {
            store.putIfAbsent(productId, Stock.restore(productId, 0, 0, now));
        }

        private static Stock copy(Stock s) {
            return Stock.restore(s.productId(), s.available(), s.reserved(), s.updatedAt());
        }
    }

    static class InMemoryReservations implements StockReservationPort {

        final Map<UUID, StockReservation> store = new HashMap<>();

        // 저장소처럼 매번 새 객체를 돌려준다 (서비스가 update 없이 객체만 바꾸면 반영되지 않게)
        @Override
        public Optional<StockReservation> findByOrderId(UUID orderId) {
            return Optional.ofNullable(store.get(orderId)).map(InMemoryReservations::copy);
        }

        @Override
        public Optional<StockReservation> findByOrderIdForUpdate(UUID orderId) {
            return findByOrderId(orderId);
        }

        @Override
        public void insert(StockReservation reservation) {
            if (store.putIfAbsent(reservation.orderId(), copy(reservation)) != null) {
                throw new IllegalStateException("duplicate reservation");
            }
        }

        @Override
        public void update(StockReservation reservation) {
            if (!store.containsKey(reservation.orderId())) {
                throw new IllegalStateException("not found");
            }
            store.put(reservation.orderId(), copy(reservation));
        }

        private static StockReservation copy(StockReservation r) {
            return StockReservation.restore(r.orderId(), r.status(), r.reason(), r.lines(), r.createdAt(),
                    r.updatedAt());
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

        @Override
        public void stockReleased(UUID orderId) {
            log.add("released:" + orderId);
        }

        @Override
        public void stockReturned(UUID orderId) {
            log.add("returned:" + orderId);
        }
    }
}
