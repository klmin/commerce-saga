package com.klmin.commerce.orderservice.order.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문 Saga 애그리거트 (주문당 하나). order-service 가 오케스트레이터로서 이 상태 머신을 DB 에 저장하고
 * 참여 서비스 응답마다 다음 할 일({@link SagaAction})을 정한다. 단계 정의는 {@link SagaState}.
 * <p>
 * 현재 단계에서 기다리는 응답이 아니면(중복, 늦게 도착, 끝난 Saga) 아무것도 바꾸지 않고 {@link SagaAction.Ignore}.
 * 그래서 at-least-once 전달에서도 단계가 되돌아가거나 커맨드가 두 번 나가지 않는다.
 */
public final class OrderSaga {

    public static final int REASON_MAX_LENGTH = 200;
    public static final String STARTED = "STARTED";

    private final UUID orderId;
    private final long userId;
    private final long pointAmount;
    private final long paidAmount;
    private final Instant createdAt;
    private final List<SagaHistoryEntry> history;
    private SagaState state;
    private boolean pointUsed;
    private String failureReason;
    private Instant updatedAt;

    private OrderSaga(UUID orderId, long userId, long pointAmount, long paidAmount, SagaState state,
                      boolean pointUsed, String failureReason, List<SagaHistoryEntry> history, Instant createdAt,
                      Instant updatedAt) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        if (pointAmount < 0 || paidAmount < 0) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        this.userId = userId;
        this.pointAmount = pointAmount;
        this.paidAmount = paidAmount;
        this.state = Objects.requireNonNull(state, "state");
        this.pointUsed = pointUsed;
        this.failureReason = failureReason;
        this.history = new ArrayList<>(history);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Saga 시작. 첫 단계는 재고 예약이다 ({@link SagaAction.ReserveStock}) */
    public static OrderSaga start(Order order, Instant now) {
        return new OrderSaga(order.id(), order.userId(), order.pointAmount(), order.paidAmount(),
                SagaState.RESERVING_STOCK, false, null,
                List.of(new SagaHistoryEntry(STARTED, SagaState.RESERVING_STOCK, null, now)), now, now);
    }

    public static OrderSaga restore(UUID orderId, long userId, long pointAmount, long paidAmount, SagaState state,
                                    boolean pointUsed, String failureReason, List<SagaHistoryEntry> history,
                                    Instant createdAt, Instant updatedAt) {
        return new OrderSaga(orderId, userId, pointAmount, paidAmount, state, pointUsed, failureReason, history,
                createdAt, updatedAt);
    }

    /** 참여 서비스 응답을 반영하고 다음 할 일을 돌려준다 */
    public SagaAction handle(SagaEventType event, String reason, Instant now) {
        SagaAction action = switch (state) {
            case RESERVING_STOCK -> switch (event) {
                case STOCK_RESERVED -> afterStockReserved();
                // 아무것도 잡지 않았으므로 보상 없이 끝난다
                case STOCK_RESERVATION_FAILED -> {
                    failureReason = truncate(reason);
                    yield cancel();
                }
                default -> ignore();
            };
            case USING_POINT -> switch (event) {
                case POINT_USED -> {
                    pointUsed = true;
                    yield afterPointUsed();
                }
                case POINT_USE_FAILED -> compensate(reason);
                default -> ignore();
            };
            case PAYING -> switch (event) {
                case PAYMENT_APPROVED -> complete();
                case PAYMENT_FAILED -> compensate(reason);
                default -> ignore();
            };
            case REFUNDING_POINT -> event == SagaEventType.POINT_REFUNDED ? moveTo(SagaState.RELEASING_STOCK,
                    new SagaAction.ReleaseStock()) : ignore();
            case RELEASING_STOCK -> event == SagaEventType.STOCK_RELEASED ? cancel() : ignore();
            case COMPLETED, CANCELLED -> ignore();
        };
        if (!(action instanceof SagaAction.Ignore)) {
            history.add(new SagaHistoryEntry(event.name(), state, truncate(reason), now));
            updatedAt = now;
        }
        return action;
    }

    private SagaAction afterStockReserved() {
        if (pointAmount > 0) {
            return moveTo(SagaState.USING_POINT, new SagaAction.UsePoint(pointAmount));
        }
        return afterPointUsed();
    }

    private SagaAction afterPointUsed() {
        if (paidAmount > 0) {
            return moveTo(SagaState.PAYING, new SagaAction.Pay(paidAmount));
        }
        return complete();
    }

    private SagaAction complete() {
        return moveTo(SagaState.COMPLETED, new SagaAction.Complete());
    }

    // 정방향의 역순으로 되돌린다: 포인트 환불(차감했을 때만) -> 재고 해제
    private SagaAction compensate(String reason) {
        failureReason = truncate(reason);
        if (pointUsed) {
            return moveTo(SagaState.REFUNDING_POINT, new SagaAction.RefundPoint());
        }
        return moveTo(SagaState.RELEASING_STOCK, new SagaAction.ReleaseStock());
    }

    private SagaAction cancel() {
        return moveTo(SagaState.CANCELLED, new SagaAction.Cancel(failureReason));
    }

    private SagaAction moveTo(SagaState next, SagaAction action) {
        state = next;
        return action;
    }

    private static SagaAction ignore() {
        return new SagaAction.Ignore();
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

    public long userId() {
        return userId;
    }

    public long pointAmount() {
        return pointAmount;
    }

    public long paidAmount() {
        return paidAmount;
    }

    public SagaState state() {
        return state;
    }

    public boolean pointUsed() {
        return pointUsed;
    }

    public String failureReason() {
        return failureReason;
    }

    public List<SagaHistoryEntry> history() {
        return Collections.unmodifiableList(history);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
