package com.klmin.commerce.inventoryservice.stock.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문 하나의 재고 예약 기록 애그리거트 (orderId 당 하나).
 * <ul>
 *   <li>같은 주문의 ReserveStock 이 다시 오면 이 기록으로 응답하고 재고는 다시 잡지 않는다</li>
 *   <li>ReleaseStock(보상)은 이 기록의 항목만큼 되돌린다. RESERVED 에서만 실제로 되돌린다</li>
 *   <li>OrderCompleted 이벤트를 받으면 CONFIRMED 로 바꾸고 예약 수량을 판매로 확정한다</li>
 * </ul>
 * 전이: RESERVED -> RELEASED | CONFIRMED. REJECTED, RELEASED, CONFIRMED 는 끝 상태.
 */
public final class StockReservation {

    public static final int REASON_MAX_LENGTH = 200;

    private final UUID orderId;
    private final String reason;
    private final List<ReservationLine> lines;
    private final Instant createdAt;
    private ReservationStatus status;
    private Instant updatedAt;

    private StockReservation(UUID orderId, ReservationStatus status, String reason, List<ReservationLine> lines,
                             Instant createdAt, Instant updatedAt) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.status = Objects.requireNonNull(status, "status");
        this.reason = truncate(reason);
        this.lines = List.copyOf(lines);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static StockReservation reserved(UUID orderId, List<ReservationLine> lines, Instant now) {
        return new StockReservation(orderId, ReservationStatus.RESERVED, null, lines, now, now);
    }

    public static StockReservation rejected(UUID orderId, String reason, List<ReservationLine> lines, Instant now) {
        return new StockReservation(orderId, ReservationStatus.REJECTED, reason, lines, now, now);
    }

    /**
     * 예약 기록 없이 ReleaseStock 이 먼저 온 경우의 기록 (되돌릴 재고 없음).
     * 이후 같은 주문의 ReserveStock 이 늦게 와도 재고를 잡지 않게 막는다.
     */
    public static StockReservation releasedWithoutReservation(UUID orderId, Instant now) {
        return new StockReservation(orderId, ReservationStatus.RELEASED, "released before reservation", List.of(),
                now, now);
    }

    public static StockReservation restore(UUID orderId, ReservationStatus status, String reason,
                                           List<ReservationLine> lines, Instant createdAt, Instant updatedAt) {
        return new StockReservation(orderId, status, reason, lines, createdAt, updatedAt);
    }

    /** 재고를 잡고 있는 상태인가 (ReserveStock 재전송 시 StockReserved 로 응답) */
    public boolean isReserved() {
        return status == ReservationStatus.RESERVED || status == ReservationStatus.CONFIRMED;
    }

    /** 보상. @return 실제로 재고를 되돌려야 하면 true (RESERVED 에서만) */
    public boolean release(Instant now) {
        if (status != ReservationStatus.RESERVED) {
            return false;
        }
        status = ReservationStatus.RELEASED;
        updatedAt = now;
        return true;
    }

    /** 주문 완료. @return 실제로 예약 수량을 확정해야 하면 true (RESERVED 에서만) */
    public boolean confirm(Instant now) {
        if (status != ReservationStatus.RESERVED) {
            return false;
        }
        status = ReservationStatus.CONFIRMED;
        updatedAt = now;
        return true;
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

    public Instant updatedAt() {
        return updatedAt;
    }
}
