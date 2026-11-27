package com.klmin.commerce.pointservice.point.application.service;

import com.klmin.commerce.pointservice.common.error.InvariantViolationException;
import com.klmin.commerce.pointservice.point.application.PointAccountNotFoundException;
import com.klmin.commerce.pointservice.point.application.port.in.GetPointBalanceUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.RefundPointUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.UsePointCommand;
import com.klmin.commerce.pointservice.point.application.port.in.UsePointUseCase;
import com.klmin.commerce.pointservice.point.application.port.out.PointAccountPort;
import com.klmin.commerce.pointservice.point.application.port.out.PointUsagePort;
import com.klmin.commerce.pointservice.point.application.port.out.ProcessedMessagePort;
import com.klmin.commerce.pointservice.point.application.port.out.SendPointReplyPort;
import com.klmin.commerce.pointservice.point.domain.PointAccount;
import com.klmin.commerce.pointservice.point.domain.PointUsage;
import com.klmin.commerce.pointservice.point.domain.PointUsageStatus;
import com.klmin.commerce.pointservice.point.domain.Points;
import com.klmin.commerce.pointservice.point.domain.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 포인트 차감·환불. 계정(PointAccount)과 주문별 사용 기록(PointUsage), 응답 outbox 를 한 트랜잭션에서 바꾼다 */
@Service
class PointService implements UsePointUseCase, RefundPointUseCase, GetPointBalanceUseCase {

    private static final Logger log = LoggerFactory.getLogger(PointService.class);

    private final PointAccountPort accountPort;
    private final PointUsagePort usagePort;
    private final SendPointReplyPort replyPort;
    private final ProcessedMessagePort processedMessagePort;
    private final Clock clock;

    PointService(PointAccountPort accountPort, PointUsagePort usagePort, SendPointReplyPort replyPort,
                 ProcessedMessagePort processedMessagePort, Clock clock) {
        this.accountPort = accountPort;
        this.usagePort = usagePort;
        this.replyPort = replyPort;
        this.processedMessagePort = processedMessagePort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void use(UUID messageId, UsePointCommand command) {
        if (alreadyProcessed(messageId, "UsePoint", command.orderId())) {
            return;
        }
        Optional<PointUsage> existing = usagePort.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            log.info("중복 포인트 사용 커맨드, 이전 결과 재응답 (orderId={}, status={})",
                    command.orderId(), existing.get().status());
            replyToUse(existing.get());
            return;
        }

        Instant now = clock.instant();
        // 잘못된 커맨드·잔액 부족은 재시도해도 같으므로 예외 대신 실패로 응답한다
        // 값 객체로 바꾸기 전에 원시 값으로 검사한다
        if (command.amount() <= 0) {
            reject(command, "invalid command: amount must be positive", now);
            return;
        }
        if (command.userId() == null) {
            reject(command, "invalid command: userId missing", now);
            return;
        }
        UserId userId = UserId.of(command.userId());
        Points amount = Points.of(command.amount());
        Optional<PointAccount> found = accountPort.findByIdForUpdate(userId);
        if (found.isEmpty()) {
            reject(command, "point account not found: userId=" + command.userId(), now);
            return;
        }
        PointAccount account = found.get();
        if (!account.canUse(amount)) {
            reject(command, "insufficient points: userId=" + command.userId(), now);
            return;
        }

        account.use(amount, now);
        accountPort.update(account);
        PointUsage usage = PointUsage.used(command.orderId(), userId, amount, now);
        usagePort.insert(usage);
        replyToUse(usage);
    }

    @Override
    @Transactional
    public void refund(UUID messageId, UUID orderId) {
        if (alreadyProcessed(messageId, "RefundPoint", orderId)) {
            return;
        }
        Instant now = clock.instant();
        Optional<PointUsage> found = usagePort.findByOrderId(orderId);
        if (found.isEmpty()) {
            // 사용 전에 환불이 온 경우: 돌려줄 것은 없지만 기록을 남겨 늦게 온 UsePoint 가 차감하지 않게 한다
            log.warn("사용 기록 없는 포인트 환불 요청 (orderId={})", orderId);
            usagePort.insert(PointUsage.refundedWithoutUsage(orderId, now));
        } else {
            PointUsage usage = found.get();
            if (usage.refund(now)) {
                PointAccount account = accountPort.findByIdForUpdate(usage.userId())
                        .orElseThrow(() -> new InvariantViolationException(
                                "point account disappeared: userId=" + usage.userId().value() + ", orderId=" + orderId));
                account.refund(usage.amount(), now);
                accountPort.update(account);
                usagePort.update(usage);
            } else {
                log.info("환불할 포인트 없음: 사용 상태 {} (orderId={})", usage.status(), orderId);
            }
        }
        // 보상은 결과가 하나뿐이다. 돌려줬든 돌려줄 게 없었든 오케스트레이터가 다음 단계로 가도록 항상 응답한다
        replyPort.pointRefunded(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public PointAccount getBalance(UUID userId) {
        return accountPort.findById(UserId.of(userId)).orElseThrow(() -> new PointAccountNotFoundException(userId));
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

    private void reject(UsePointCommand command, String reason, Instant now) {
        PointUsage usage = PointUsage.rejected(command.orderId(), command.userId(), command.amount(), reason, now);
        usagePort.insert(usage);
        replyToUse(usage);
    }

    // UsePoint 에 대한 응답. REFUNDED 는 이미 보상이 끝난 주문이라 실패로 응답한다 (오케스트레이터는 늦은 응답을 무시)
    private void replyToUse(PointUsage usage) {
        if (usage.status() == PointUsageStatus.USED) {
            replyPort.pointUsed(usage.orderId());
        } else if (usage.status() == PointUsageStatus.REFUNDED) {
            replyPort.pointUseFailed(usage.orderId(), "point usage already refunded");
        } else {
            replyPort.pointUseFailed(usage.orderId(), usage.reason());
        }
    }
}
