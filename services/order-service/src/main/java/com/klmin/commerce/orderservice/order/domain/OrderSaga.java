package com.klmin.commerce.orderservice.order.domain;

import java.time.Duration;
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
 * <p>
 * 응답이 오지 않는 단계(참여 서비스 장애, 커맨드·응답이 DLT 로 감)는 {@link #onTimeout} 이 정리한다:
 * 같은 커맨드를 다시 보내고(참여 서비스는 주문별 기록으로 처음 결과를 다시 응답), 그래도 안 되면 역순 보상 (ADR 0007).
 */
public final class OrderSaga {

    public static final int REASON_MAX_LENGTH = 200;
    public static final String STARTED = "STARTED";
    /** 응답이 없어 현재 단계의 커맨드를 다시 보냄 (단계는 그대로) */
    public static final String TIMEOUT_RESEND = "TIMEOUT_RESEND";
    /** 재전송해도 응답이 없어 정방향을 포기하고 보상으로 넘어감 */
    public static final String TIMEOUT_GIVE_UP = "TIMEOUT_GIVE_UP";

    private final UUID orderId;
    private final long userId;
    private final long pointAmount;
    private final long paidAmount;
    private final Instant createdAt;
    private final List<SagaHistoryEntry> history;
    private SagaState state;
    private boolean pointUsed;
    private String failureReason;
    private int stepResends;
    private Instant updatedAt;

    private OrderSaga(UUID orderId, long userId, long pointAmount, long paidAmount, SagaState state,
                      boolean pointUsed, String failureReason, int stepResends, List<SagaHistoryEntry> history,
                      Instant createdAt, Instant updatedAt) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        if (pointAmount < 0 || paidAmount < 0) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        if (stepResends < 0) {
            throw new IllegalArgumentException("stepResends must not be negative");
        }
        this.userId = userId;
        this.pointAmount = pointAmount;
        this.paidAmount = paidAmount;
        this.state = Objects.requireNonNull(state, "state");
        this.pointUsed = pointUsed;
        this.failureReason = failureReason;
        this.stepResends = stepResends;
        this.history = new ArrayList<>(history);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Saga 시작. 첫 단계는 재고 예약이다 ({@link SagaAction.ReserveStock}) */
    public static OrderSaga start(Order order, Instant now) {
        return new OrderSaga(order.id(), order.userId(), order.pointAmount(), order.paidAmount(),
                SagaState.RESERVING_STOCK, false, null, 0,
                List.of(new SagaHistoryEntry(STARTED, SagaState.RESERVING_STOCK, null, now)), now, now);
    }

    public static OrderSaga restore(UUID orderId, long userId, long pointAmount, long paidAmount, SagaState state,
                                    boolean pointUsed, String failureReason, int stepResends,
                                    List<SagaHistoryEntry> history, Instant createdAt, Instant updatedAt) {
        return new OrderSaga(orderId, userId, pointAmount, paidAmount, state, pointUsed, failureReason, stepResends,
                history, createdAt, updatedAt);
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
            // 처음 실패 사유(결제 타임아웃)는 그대로 두고 나머지를 역순으로 되돌린다
            case CANCELLING_PAYMENT -> event == SagaEventType.PAYMENT_CANCELLED ? compensateAfterPayment() : ignore();
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

    /**
     * 현재 단계에서 {@code stepTimeout} 넘게 아무 응답이 없을 때 할 일. 아직 시간이 안 됐거나 끝난 Saga 면 {@link SagaAction.Ignore}.
     * <ul>
     *   <li>재고 예약·포인트 차감: {@code maxResends} 번까지 같은 커맨드를 다시 보낸다. 그래도 응답이 없으면
     *       앞 단계가 반영됐는지 알 수 없으므로 <b>반영됐다고 보고</b> 역순 보상한다 (보상 커맨드는 앞 단계가 없었어도 안전)</li>
     *   <li>결제: 결과가 애매하므로(승인됐을 수 있음) 바로 보상하지 않고 {@code maxPaymentResends} 번까지 다시 묻는다.
     *       결제 서비스는 같은 주문의 Pay 에 기록된 결과를 다시 응답하고, PG 타임아웃이면 PG 조회로 확정한 뒤 응답한다.
     *       그래도 응답이 없으면 결제 취소({@link SagaAction.CancelPayment})부터 보상한다. 늦게 승인되면 결제 서비스가 환불한다</li>
     *   <li>보상: 포기할 수 없으므로 끝까지 다시 보낸다</li>
     * </ul>
     * 재전송할 때마다 {@code updatedAt} 이 바뀌어 다음 재전송은 다시 {@code stepTimeout} 뒤다.
     */
    public SagaAction onTimeout(Instant now, SagaTimeoutPolicy policy) {
        Duration stepTimeout = policy.stepTimeout();
        if (state.isFinished() || now.isBefore(updatedAt.plus(stepTimeout))) {
            return ignore();
        }
        SagaState timedOut = state;
        int limit = timedOut == SagaState.PAYING ? policy.maxPaymentResends() : policy.maxResends();
        String event;
        String reason;
        SagaAction action;
        if (stepResends < limit || timedOut.isCompensating()) {
            stepResends++;
            event = TIMEOUT_RESEND;
            reason = "no reply in " + timedOut + " for " + stepTimeout.toSeconds() + "s, resend #" + stepResends;
            action = currentCommand();
        } else {
            event = TIMEOUT_GIVE_UP;
            reason = "timeout: no reply in " + timedOut + " after " + limit + " resends";
            failureReason = truncate(reason);
            action = switch (timedOut) {
                case RESERVING_STOCK -> moveTo(SagaState.RELEASING_STOCK, new SagaAction.ReleaseStock());
                // 포인트 차감 여부를 모른다. 환불은 차감 기록이 없어도 안전하다
                case USING_POINT -> moveTo(SagaState.REFUNDING_POINT, new SagaAction.RefundPoint());
                // 승인 여부를 모른다. 결제 서비스가 취소를 기록하고, 승인됐거나 늦게 승인되면 환불한다
                case PAYING -> moveTo(SagaState.CANCELLING_PAYMENT, new SagaAction.CancelPayment(paidAmount));
                default -> throw new IllegalStateException("cannot give up in " + timedOut);
            };
        }
        history.add(new SagaHistoryEntry(event, state, truncate(reason), now));
        updatedAt = now;
        return action;
    }

    /** 현재 단계에서 응답을 기다리는 커맨드 */
    private SagaAction currentCommand() {
        return switch (state) {
            case RESERVING_STOCK -> new SagaAction.ReserveStock();
            case USING_POINT -> new SagaAction.UsePoint(pointAmount);
            case PAYING -> new SagaAction.Pay(paidAmount);
            case CANCELLING_PAYMENT -> new SagaAction.CancelPayment(paidAmount);
            case REFUNDING_POINT -> new SagaAction.RefundPoint();
            case RELEASING_STOCK -> new SagaAction.ReleaseStock();
            case COMPLETED, CANCELLED -> throw new IllegalStateException("finished saga has no command: " + state);
        };
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

    // 결제 취소가 접수된 뒤: 포인트 환불(차감했을 때만) -> 재고 해제. 실패 사유는 결제 타임아웃 그대로
    private SagaAction compensateAfterPayment() {
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
        stepResends = 0;
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

    /** 현재 단계에서 타임아웃으로 커맨드를 다시 보낸 횟수. 단계가 바뀌면 0 */
    public int stepResends() {
        return stepResends;
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
