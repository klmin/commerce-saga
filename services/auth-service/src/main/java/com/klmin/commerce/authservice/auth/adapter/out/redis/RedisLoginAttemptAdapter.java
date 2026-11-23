package com.klmin.commerce.authservice.auth.adapter.out.redis;

import com.klmin.commerce.authservice.auth.application.port.out.LoginAttemptPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 아이디별 로그인 실패 횟수 {@code auth:login-fail:<loginId>}. 첫 실패 때 만료를 걸어 그 창 안의 실패만 센다 (fixed window).
 * 아이디 기준이라 남이 일부러 틀려 내 계정을 잠글 수 있다. 잠금이 짧고(기본 15분) 비밀번호 대입을 막는 쪽을 택했다 (ADR 0008).
 */
@Component
class RedisLoginAttemptAdapter implements LoginAttemptPort {

    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local n = redis.call('incr', KEYS[1])
            if n == 1 then redis.call('pexpire', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;

    RedisLoginAttemptAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Duration> lockedFor(String loginId, int maxFailures) {
        String count = redis.opsForValue().get(key(loginId));
        if (count == null) {
            return Optional.empty();
        }
        long failures = failures(count);
        if (failures < 0) {
            redis.delete(key(loginId)); // 깨진 값(만료도 없을 수 있음)이 계정을 영영 잠그지 않게 지운다
            return Optional.empty();
        }
        if (failures < maxFailures) {
            return Optional.empty();
        }
        Long ttl = redis.getExpire(key(loginId), TimeUnit.MILLISECONDS);
        return Optional.of(ttl == null || ttl <= 0 ? Duration.ofSeconds(1) : Duration.ofMillis(ttl));
    }

    @Override
    public void recordFailure(String loginId, Duration window) {
        redis.execute(INCREMENT, List.of(key(loginId)), Long.toString(window.toMillis()));
    }

    @Override
    public void reset(String loginId) {
        redis.delete(key(loginId));
    }

    // INCR 로만 쓰는 값이라 숫자가 아니면 깨진 것 (-1)
    private static long failures(String count) {
        try {
            return Long.parseLong(count);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String key(String loginId) {
        return "auth:login-fail:" + loginId;
    }
}
