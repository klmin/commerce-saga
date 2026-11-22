package com.klmin.commerce.inventoryservice.stock.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문 하나의 재고 예약 결과 애그리거트 (orderId 당 하나).
 * 같은 주문의 ReserveStock 이 다시 오면 이 결과로 같은 응답을 돌려주고 재고는 다시 잡지 않는다.
 * 2단계 ReleaseStock(보상)도 이 기록의 항목만큼 되돌린다.
 */
public final class StockReservation {

    public static final int REASON_MAX_LENGTH = 200;

    private final UUID orderId;
    private final ReservationStatus status;
    private final String reason;
    private final List<ReservationLine> lines;
    private final Instant createdAt;

    private StockReservation(UUID orderId, ReservationStatus status, String reason, List<ReservationLine> lines,
                             Instant createdAt) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.status = Objects.requireNonNull(status, "status");
        this.reason = truncate(reason);
        this.lines = List.copyOf(lines);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static StockReservation reserved(UUID orderId, List<ReservationLine> lines, Instant now) {
        return new StockReservation(orderId, ReservationStatus.RESERVED, null, lines, now);
    }

    public static StockReservation rejected(UUID orderId, String reason, List<ReservationLine> lines, Instant now) {
        return new StockReservation(orderId, ReservationStatus.REJECTED, reason, lines, now);
    }

    public static StockReservation restore(UUID orderId, ReservationStatus status, String reason,
                                           List<ReservationLine> lines, Instant createdAt) {
        return new StockReservation(orderId, status, reason, lines, createdAt);
    }

    public boolean isReserved() {
        return status == ReservationStatus.RESERVED;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.strip();
        return trimmed.length() > REASON_MAX_LENGTH ? trimmed.substring(0, REASON_MAX_LENGTH) : trimmed;
    }

    public UUID orderId() {
        return orderId;
    }

    public ReservationStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public List<ReservationLine> lines() {
        return lines;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
