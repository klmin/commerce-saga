package com.klmin.commerce.inventoryservice.stock.application.service;

import com.klmin.commerce.inventoryservice.stock.application.StockNotFoundException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.GetStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.out.SendStockReplyPort;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockPort;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockReservationPort;
import com.klmin.commerce.inventoryservice.stock.domain.ReservationLine;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 재고 예약. 한 트랜잭션에서 여러 상품의 Stock 애그리거트와 StockReservation 을 함께 바꾼다
 * ("트랜잭션 하나 = 애그리거트 하나"의 예외, 주문 단위 전부-아니면-전무 예약이 필요해서. ADR 0002).
 */
@Service
class StockService implements ReserveStockUseCase, GetStockUseCase {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockPort stockPort;
    private final StockReservationPort reservationPort;
    private final SendStockReplyPort replyPort;
    private final Clock clock;

    StockService(StockPort stockPort, StockReservationPort reservationPort, SendStockReplyPort replyPort,
                 Clock clock) {
        this.stockPort = stockPort;
        this.reservationPort = reservationPort;
        this.replyPort = replyPort;
        this.clock = clock;
    }

    // 재고 변경 + 예약 기록 + 응답(outbox) = 한 트랜잭션
    @Override
    @Transactional
    public void reserve(ReserveStockCommand command) {
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

    private void reply(StockReservation reservation) {
        if (reservation.isReserved()) {
            replyPort.stockReserved(reservation.orderId());
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
