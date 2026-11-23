package com.klmin.commerce.authservice.auth.application.port.out;

import java.time.Duration;
import java.util.Optional;

/** 아이디별 로그인 실패 횟수 (Redis) */
public interface LoginAttemptPort {

    /** 실패가 {@code maxFailures} 번 이상이면 남은 잠금 시간 */
    Optional<Duration> lockedFor(String loginId, int maxFailures);

    /** 실패 한 번. 첫 실패부터 {@code window} 가 지나면 횟수가 사라진다 */
    void recordFailure(String loginId, Duration window);

    void reset(String loginId);
}
