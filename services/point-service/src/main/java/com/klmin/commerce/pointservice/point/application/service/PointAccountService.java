package com.klmin.commerce.pointservice.point.application.service;

import com.klmin.commerce.pointservice.point.application.port.in.OpenPointAccountCommand;
import com.klmin.commerce.pointservice.point.application.port.in.OpenPointAccountUseCase;
import com.github.f4b6a3.uuid.UuidCreator;
import com.klmin.commerce.pointservice.point.application.port.out.PointAccountPort;
import com.klmin.commerce.pointservice.point.application.port.out.PointGrantPort;
import com.klmin.commerce.pointservice.point.application.port.out.ProcessedMessagePort;
import com.klmin.commerce.pointservice.point.domain.PointAccount;
import com.klmin.commerce.pointservice.point.domain.PointGrant;
import com.klmin.commerce.pointservice.point.domain.Points;
import com.klmin.commerce.pointservice.point.domain.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * 가입 → 포인트 계정 (auth.events 코레오그래피, 후속 2 A-3-2).
 * 멱등 두 겹: 같은 메시지의 재전달은 processed_message, 내용이 같은 다른 메시지는 계정 유니크(user_id)로 거른다.
 * 계정을 만들 때 가입 축하 지급 기록(point_grants)도 같은 트랜잭션에 남긴다(포인트 내역, F-2a). 계정이 이미 있으면 남기지 않는다.
 * 응답은 없다 (이벤트 소비자). 계정이 아직 없을 때 온 주문은 지금처럼 포인트 단계 실패 → 보상.
 */
@Service
@EnableConfigurationProperties(PointAccountProperties.class)
class PointAccountService implements OpenPointAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(PointAccountService.class);

    private final PointAccountPort accountPort;
    private final PointGrantPort grantPort;
    private final ProcessedMessagePort processedMessagePort;
    private final PointAccountProperties properties;
    private final Clock clock;

    PointAccountService(PointAccountPort accountPort, PointGrantPort grantPort,
                        ProcessedMessagePort processedMessagePort, PointAccountProperties properties, Clock clock) {
        this.accountPort = accountPort;
        this.grantPort = grantPort;
        this.processedMessagePort = processedMessagePort;
        this.properties = properties;
        this.clock = clock;
    }

    // 처리 기록 + 계정 생성 + 지급 기록 = 한 트랜잭션 (처리 기록만 남고 계정이 없는 일이 없다)
    @Override
    @Transactional
    public void open(UUID messageId, OpenPointAccountCommand command) {
        if (!processedMessagePort.markProcessed(messageId)) {
            log.info("이미 처리한 메시지 건너뜀: UserRegistered (messageId={}, userId={})", messageId, command.userId());
            return;
        }
        PointAccount account = PointAccount.open(UserId.of(command.userId()), Points.of(properties.signupBonus()),
                clock.instant());
        if (accountPort.insertIfAbsent(account)) {
            if (account.balance().isPositive()) {
                grantPort.save(PointGrant.signupBonus(UuidCreator.getTimeOrderedEpoch(), account.userId(),
                        account.balance(), account.updatedAt()));
            }
            log.info("포인트 계정 생성 (userId={}, 가입 축하 {}P)", command.userId(), properties.signupBonus());
        } else {
            log.info("이미 있는 포인트 계정, 그대로 둠 (userId={})", command.userId());
        }
    }
}
