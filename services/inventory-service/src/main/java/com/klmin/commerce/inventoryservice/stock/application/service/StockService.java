package com.klmin.commerce.inventoryservice.stock.application.service;

import com.klmin.commerce.inventoryservice.common.error.InvariantViolationException;
import com.klmin.commerce.inventoryservice.stock.application.StockNotFoundException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ConfirmStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReleaseStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.GetStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.out.ProcessedMessagePort;
import com.klmin.commerce.inventoryservice.stock.application.port.out.SendStockReplyPort;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockPort;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockReservationPort;
import com.klmin.commerce.inventoryservice.stock.domain.ReservationLine;
import com.klmin.commerce.inventoryservice.stock.domain.ReservationStatus;
import com.klmin.commerce.inventoryservice.stock.domain.Stock;
import com.klmin.commerce.inventoryservice.stock.domain.StockReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 재고 예약. 한 트랜잭션에서 여러 상품의 Stock 애그리거트와 StockReservation 을 함께 바꾼다
 * ("트랜잭션 하나 = 애그리거트 하나"의 예외, 주문 단위 전부-아니면-전무 예약이 필요해서. ADR 0002).
 */
@Service
class StockService implements ReserveStockUseCase, ReleaseStockUseCase, ConfirmStockUseCase, GetStockUseCase {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockPort stockPort;
    private final StockReservationPort reservationPort;
    private final SendStockReplyPort replyPort;
    private final ProcessedMessagePort processedMessagePort;
    private final Clock clock;

    StockService(StockPort stockPort, StockReservationPort reservationPort, SendStockReplyPort replyPort,
                 ProcessedMessagePort processedMessagePort, Clock clock) {
        this.stockPort = stockPort;
        this.reservationPort = reservationPort;
        this.replyPort = replyPort;
        this.processedMessagePort = processedMessagePort;
        this.clock = clock;
    }

    // 재고 변경 + 예약 기록 + 응답(outbox) = 한 트랜잭션
    @Override
    @Transactional
    public void reserve(UUID messageId, ReserveStockCommand command) {
        if (alreadyProcessed(messageId, "ReserveStock", command.orderId())) {
            return;
        }
        Optional<StockReservation> existing = reservationPort.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            // 중복 커맨드: 재고는 다시 잡지 않고 처음 결과를 다시 응답한다 (오케스트레이터는 중복 응답을 무시)
            log.info("중복 재고 예약 커맨드, 이전 결과 재응답 (orderId={}, status={})",
                    command.orderId(), existing.get().status());
            reply(existing.get());
            return;
        }

        Instant now = clock.instant();
        List<ReservationLine> lines = command.lines().stream()
                .map(l -> new ReservationLine(l.productId(), l.quantity()))
                .toList();

        String invalid = validate(lines);
        if (invalid != null) {
            reject(command, lines, invalid, now);
            return;
        }

        Map<Long, Stock> stocks = stockPort.findAllForUpdate(lines.stream().map(ReservationLine::productId).toList())
                .stream()
                .collect(Collectors.toMap(Stock::productId, Function.identity()));
        for (ReservationLine line : lines) {
            Stock stock = stocks.get(line.productId());
            if (stock == null) {
                reject(command, lines, "unknown product: productId=" + line.productId(), now);
                return;
            }
            if (!stock.canReserve(line.quantity())) {
                reject(command, lines, "insufficient stock: productId=" + line.productId(), now);
                return;
            }
        }

        for (ReservationLine line : lines) {
            stocks.get(line.productId()).reserve(line.quantity(), now);
        }
        stockPort.update(stocks.values());
        StockReservation reservation = StockReservation.reserved(command.orderId(), lines, now);
        reservationPort.insert(reservation);
        reply(reservation);
    }

    // 재고 되돌림 + 예약 기록 변경 + 응답(outbox) = 한 트랜잭션
    @Override
    @Transactional
    public void release(UUID messageId, UUID orderId) {
        if (alreadyProcessed(messageId, "ReleaseStock", orderId)) {
            return;
        }
        Instant now = clock.instant();
        Optional<StockReservation> found = reservationPort.findByOrderIdForUpdate(orderId);
        if (found.isEmpty()) {
            // 예약 전에 해제가 온 경우: 되돌릴 것은 없지만 기록을 남겨 늦게 온 ReserveStock 이 재고를 잡지 않게 한다
            log.warn("예약 기록 없는 재고 해제 요청 (orderId={})", orderId);
            reservationPort.insert(StockReservation.releasedWithoutReservation(orderId, now));
        } else {
            StockReservation reservation = found.get();
            if (reservation.release(now)) {
                applyToStocks(reservation, (stock, quantity) -> stock.release(quantity, now));
                reservationPort.update(reservation);
            } else {
                log.info("재고 해제할 것 없음: 예약 상태 {} (orderId={})", reservation.status(), orderId);
            }
        }
        // 보상은 결과가 하나뿐이다. 되돌렸든 되돌릴 게 없었든 오케스트레이터가 다음 단계로 가도록 항상 응답한다
        replyPort.stockReleased(orderId);
    }

    @Override
    @Transactional
    public void confirm(UUID messageId, UUID orderId) {
        if (alreadyProcessed(messageId, "OrderCompleted", orderId)) {
            return;
        }
        Instant now = clock.instant();
        Optional<StockReservation> found = reservationPort.findByOrderIdForUpdate(orderId);
        if (found.isEmpty()) {
            log.warn("예약 기록 없는 주문 완료 이벤트 (orderId={})", orderId);
            return;
        }
        StockReservation reservation = found.get();
        if (reservation.confirm(now)) {
            applyToStocks(reservation, (stock, quantity) -> stock.confirm(quantity, now));
            reservationPort.update(reservation);
        } else {
            log.info("판매 확정할 것 없음: 예약 상태 {} (orderId={})", reservation.status(), orderId);
        }
    }

    // 같은 메시지의 재전달(relay 재전송, 오프셋 되감기)은 아무것도 바꾸지 않고 응답도 다시 보내지 않는다.
    // 내용이 같은 다른 메시지(예: 오케스트레이터의 재요청)는 ID 가 달라 여기를 지나고 주문별 기록으로 걸러진다
    private boolean alreadyProcessed(UUID messageId, String type, UUID orderId) {
        if (processedMessagePort.markProcessed(messageId)) {
            return false;
        }
        log.info("이미 처리한 메시지 건너뜀: {} (messageId={}, orderId={})", type, messageId, orderId);
        return true;
    }

    private void applyToStocks(StockReservation reservation, BiConsumer<Stock, Integer> change) {
        Map<Long, Stock> stocks = stockPort.findAllForUpdate(
                        reservation.lines().stream().map(ReservationLine::productId).toList())
                .stream()
                .collect(Collectors.toMap(Stock::productId, Function.identity()));
        for (ReservationLine line : reservation.lines()) {
            Stock stock = stocks.get(line.productId());
            if (stock == null) {
                throw new InvariantViolationException(
                        "reserved product disappeared: productId=" + line.productId() + ", orderId="
                                + reservation.orderId());
            }
            change.accept(stock, line.quantity());
        }
        stockPort.update(stocks.values());
    }

    @Override
    @Transactional(readOnly = true)
    public Stock getStock(long productId) {
        return stockPort.findById(productId).orElseThrow(() -> new StockNotFoundException(productId));
    }

    private void reject(ReserveStockCommand command, List<ReservationLine> lines, String reason, Instant now) {
        StockReservation reservation = StockReservation.rejected(command.orderId(), reason, lines, now);
        reservationPort.insert(reservation);
        reply(reservation);
    }

    // ReserveStock 에 대한 응답. RELEASED 는 이미 보상이 끝난 주문이라 실패로 응답한다 (오케스트레이터는 늦은 응답을 무시)
    private void reply(StockReservation reservation) {
        if (reservation.isReserved()) {
            replyPort.stockReserved(reservation.orderId());
        } else if (reservation.status() == ReservationStatus.RELEASED) {
            replyPort.stockReservationFailed(reservation.orderId(), "reservation already released");
        } else {
            replyPort.stockReservationFailed(reservation.orderId(), reservation.reason());
        }
    }

    // 잘못된 커맨드는 재시도해도 바뀌지 않으므로 예외 대신 실패로 응답한다
    private static String validate(List<ReservationLine> lines) {
        if (lines.isEmpty()) {
            return "invalid command: no lines";
        }
        Set<Long> productIds = new HashSet<>();
        for (ReservationLine line : lines) {
            if (line.quantity() <= 0) {
                return "invalid command: quantity must be positive (productId=" + line.productId() + ")";
            }
            if (!productIds.add(line.productId())) {
                return "invalid command: duplicate productId=" + line.productId();
            }
        }
        return null;
    }
}
