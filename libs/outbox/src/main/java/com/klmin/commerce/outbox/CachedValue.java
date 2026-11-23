package com.klmin.commerce.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 조회 결과를 잠깐 재사용한다. 게이지 여러 개가 한 번의 스크레이프에서 같은 쿼리를 반복하지 않게.
 * 조회가 실패하면 예외를 그대로 던진다 (Micrometer 는 게이지 값을 NaN 으로 남긴다).
 */
final class CachedValue<T> {

    private final Supplier<T> loader;
    private final Clock clock;
    private final Duration ttl;
    private T value;
    private Instant loadedAt;

    CachedValue(Supplier<T> loader, Clock clock, Duration ttl) {
        this.loader = loader;
        this.clock = clock;
        this.ttl = ttl;
    }

    synchronized T get() {
        Instant now = clock.instant();
        if (value == null || !now.isBefore(loadedAt.plus(ttl))) {
            value = loader.get();
            loadedAt = now;
        }
        return value;
    }
}
