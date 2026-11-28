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
    /** 사용자가 주문 취소를 요청함 (ADR 0020) */
    public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    /** 사용자 취소로 끝난 주문의 취소 사유 */
    public static final String USER_CANCEL_REASON = "사용자 취소";

    private final UUID orderId;
    private final UserId userId;
    private final Points pointAmount;
    private final Money paidAmount;
    private final Instant createdAt;
    private final List<SagaHistoryEntry> history;
    private SagaState state;
    private boolean pointUsed;
    private String failureReason;
    private int stepResends;
    private boolean cancelRequested;
    private boolean afterCompletion;
    private Instant updatedAt;

    private OrderSaga(UUID orderId, UserId userId, Points pointAmount, Money paidAmount, SagaState state,
                      boolean pointUsed, String failureReason, int stepResends, boolean cancelRequested,
                      boolean afterCompletion, List<SagaHistoryEntry> history, Instant createdAt, Instant updatedAt) {
        if (afterCompletion && !cancelRequested) {
            throw new IllegalArgumentException("afterCompletion requires cancelRequested");
        }
        this.cancelRequested = cancelRequested;
        this.afterCompletion = afterCompletion;
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        if (stepResends < 0) {
            throw new IllegalArgumentException("stepResends must not be negative");
        }
        this.userId = Objects.requireNonNull(userId, "userId");
        this.pointAmount = Objects.requireNonNull(pointAmount, "pointAmount");
        this.paidAmount = Objects.requireNonNull(paidAmount, "paidAmount");
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
                SagaState.RESERVING_STOCK, false, null, 0, false, false,
                List.of(new SagaHistoryEntry(STARTED, SagaState.RESERVING_STOCK, null, now)), now, now);
    }

    public static OrderSaga restore(UUID orderId, UserId userId, Points pointAmount, Money paidAmount, SagaState state,
                                    boolean pointUsed, String failureReason, int stepResends, boolean cancelRequested,
                                    boolean afterCompletion, List<SagaHistoryEntry> history, Instant createdAt,
                                    Instant updatedAt) {
        return new OrderSaga(orderId, userId, pointAmount, paidAmount, state, pointUsed, failureReason, stepResends,
                cancelRequested, afterCompletion, history, createdAt, updatedAt);
    }

    /** 취소 요청이 없는 Saga (취소 도입 전 테스트·데이터) */
    public static OrderSaga restore(UUID orderId, UserId userId, Points pointAmount, Money paidAmount, SagaState state,
                                    boolean pointUsed, String failureReason, int stepResends,
                                    List<SagaHistoryEntry> history, Instant createdAt, Instant updatedAt) {
        return restore(orderId, userId, pointAmount, paidAmount, state, pointUsed, failureReason, stepResends, false,
                false, history, createdAt, updatedAt);
    }

    /**
     * 사용자 취소 요청 (ADR 0020). 돌려준 커맨드를 보내야 하면 그 커맨드, 아니면 {@link SagaAction.Ignore}.
     * <ul>
     *   <li>진행 중(재고 예약·포인트 차감·결제): 요청만 기록한다. 진행 중인 단계의 응답이 오면 {@link #handle} 이 보상으로 돌린다</li>
     *   <li>보상 중: 이미 취소로 가는 중이라 요청만 기록한다</li>
     *   <li>완료: 결제 취소(환불) → 포인트 환불 → 재고 반환을 시작한다 (결제 금액이 0 이거나 포인트를 쓰지 않았으면 그 단계는 건너뜀)</li>
     *   <li>취소됨, 이미 요청함: 아무것도 바꾸지 않는다 (멱등)</li>
     * </ul>
     */
    public SagaAction requestCancel(Instant now) {
        if (cancelRequested || state == SagaState.CANCELLED) {
            return ignore();
        }
        cancelRequested = true;
        SagaAction action = ignore();
        if (state == SagaState.COMPLETED) {
            afterCompletion = true;
            failureReason = USER_CANCEL_REASON;
            action = paidAmount.isPositive()
                    ? moveTo(SagaState.CANCELLING_PAYMENT, new SagaAction.CancelPayment(paidAmount))
                    : compensateAfterPayment();
        }
        history.add(new SagaHistoryEntry(CANCEL_REQUESTED, state, null, now));
        updatedAt = now;
        return action;
    }

    /**
     * 참여 서비스 응답을 반영하고 다음 할 일을 돌려준다.
     * 취소 요청이 있으면 정방향 성공 응답에서 다음 단계로 가지 않고 보상으로 돌린다 (ADR 0020). 실패 응답은 원래대로 보상한다
     */
    public SagaAction handle(SagaEventType event, String reason, Instant now) {
        SagaAction action = switch (state) {
            case RESERVING_STOCK -> switch (event) {
                case STOCK_RESERVED -> cancelRequested ? compensate(USER_CANCEL_REASON) : afterStockReserved();
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
                    yield cancelRequested ? compensate(USER_CANCEL_REASON) : afterPointUsed();
                }
                case POINT_USE_FAILED -> compensate(reason);
                default -> ignore();
            };
            case PAYING -> switch (event) {
                // 승인된 결제는 결제 취소(환불)부터 되돌린다
                case PAYMENT_APPROVED -> {
                    if (!cancelRequested) {
                        yield complete();
                    }
                    failureReason = USER_CANCEL_REASON;
                    yield moveTo(SagaState.CANCELLING_PAYMENT, new SagaAction.CancelPayment(paidAmount));
                }
                case PAYMENT_FAILED -> compensate(reason);
                default -> ignore();
            };
            // 처음 실패 사유(결제 타임아웃)는 그대로 두고 나머지를 역순으로 되돌린다
            case CANCELLING_PAYMENT -> event == SagaEventType.PAYMENT_CANCELLED ? compensateAfterPayment() : ignore();
            case REFUNDING_POINT -> event == SagaEventType.POINT_REFUNDED ? restoreStock() : ignore();
            case RELEASING_STOCK -> event == SagaEventType.STOCK_RELEASED ? cancel() : ignore();
            case RETURNING_STOCK -> event == SagaEventType.STOCK_RETURNED ? cancel() : ignore();
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
            case RETURNING_STOCK -> new SagaAction.ReturnStock();
            case COMPLETED, CANCELLED -> throw new IllegalStateException("finished saga has no command: " + state);
        };
    }

    private SagaAction afterStockReserved() {
        if (pointAmount.isPositive()) {
            return moveTo(SagaState.USING_POINT, new SagaAction.UsePoint(pointAmount));
        }
        return afterPointUsed();
    }

    private SagaAction afterPointUsed() {
        if (paidAmount.isPositive()) {
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

    // 결제 취소가 접수된 뒤: 포인트 환불(차감했을 때만) -> 재고. 실패 사유는 결제 타임아웃·사용자 취소 그대로
    private SagaAction compensateAfterPayment() {
        if (pointUsed) {
            return moveTo(SagaState.REFUNDING_POINT, new SagaAction.RefundPoint());
        }
        return restoreStock();
    }

    // 마지막 재고 단계: 완료 후 취소면 팔린 재고를 다시 들이고(ReturnStock), 아니면 잡은 예약을 푼다(ReleaseStock)
    private SagaAction restoreStock() {
        return afterCompletion
                ? moveTo(SagaState.RETURNING_STOCK, new SagaAction.ReturnStock())
                : moveTo(SagaState.RELEASING_STOCK, new SagaAction.ReleaseStock());
    }

    private SagaAction cancel() {
        return moveTo(SagaState.CANCELLED, new SagaAction.Cancel(failureReason, afterCompletion));
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

    public boolean isOwnedBy(UserId userId) {
        return this.userId.equals(userId);
    }

    public UserId userId() {
        return userId;
    }

    public Points pointAmount() {
        return pointAmount;
    }

    public Money paidAmount() {
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

    /** 사용자가 취소를 요청했다 (ADR 0020) */
    public boolean cancelRequested() {
        return cancelRequested;
    }

    /** 완료된 주문을 취소하는 중이거나 그렇게 취소됐다 (재고는 ReturnStock 으로 되돌린다) */
    public boolean afterCompletion() {
        return afterCompletion;
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
