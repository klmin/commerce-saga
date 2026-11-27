package com.klmin.commerce.authservice.auth.application.service;

import com.klmin.commerce.authservice.auth.application.DuplicateLoginIdException;
import com.klmin.commerce.authservice.auth.application.port.out.LoginAttemptPort;
import com.klmin.commerce.authservice.auth.application.port.out.PasswordHasher;
import com.klmin.commerce.authservice.auth.application.port.out.UserPort;
import com.klmin.commerce.authservice.auth.domain.User;
import com.klmin.commerce.authservice.auth.domain.UserId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** AuthService 단위 테스트용 인메모리 포트 구현 */
final class InMemoryAuthPorts {

    private InMemoryAuthPorts() {
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    static final class Users implements UserPort {
        final Map<UserId, User> byId = new HashMap<>();

        @Override
        public User save(User user) {
            if (!byId.containsKey(user.id()) && findByLoginId(user.loginId()).isPresent()) {
                throw new DuplicateLoginIdException();
            }
            byId.put(user.id(), user);
            return user;
        }

        @Override
        public Optional<User> findByLoginId(String loginId) {
            return byId.values().stream().filter(u -> u.loginId().equals(loginId)).findFirst();
        }

        @Override
        public Optional<User> findById(UserId id) {
            return Optional.ofNullable(byId.get(id));
        }
    }

    /** 발행한 회원 이벤트를 기록한다 (outbox 대역) */
    static final class UserEvents implements com.klmin.commerce.authservice.auth.application.port.out.UserEventPort {
        final java.util.List<String> log = new java.util.ArrayList<>();

        @Override
        public void userRegistered(UUID userId, Instant registeredAt) {
            log.add("UserRegistered:" + userId + "@" + registeredAt);
        }
    }

    static final class Hasher implements PasswordHasher {
        int dummyMatches;

        @Override
        public String hash(String rawPassword) {
            return "hashed:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            return passwordHash.equals("hashed:" + rawPassword);
        }

        @Override
        public void matchAgainstDummy(String rawPassword) {
            dummyMatches++;
        }
    }

    static final class LoginAttempts implements LoginAttemptPort {
        private final Clock clock;
        private final Map<String, Integer> failures = new HashMap<>();
        private final Map<String, Instant> windowEnds = new HashMap<>();

        LoginAttempts(Clock clock) {
            this.clock = clock;
        }

        @Override
        public Optional<Duration> lockedFor(String loginId, int maxFailures) {
            expire(loginId);
            if (failures.getOrDefault(loginId, 0) < maxFailures) {
                return Optional.empty();
            }
            return Optional.of(Duration.between(clock.instant(), windowEnds.get(loginId)));
        }

        @Override
        public void recordFailure(String loginId, Duration window) {
            expire(loginId);
            if (failures.merge(loginId, 1, Integer::sum) == 1) {
                windowEnds.put(loginId, clock.instant().plus(window));
            }
        }

        @Override
        public void reset(String loginId) {
            failures.remove(loginId);
            windowEnds.remove(loginId);
        }

        private void expire(String loginId) {
            Instant end = windowEnds.get(loginId);
            if (end != null && !clock.instant().isBefore(end)) {
                reset(loginId);
            }
        }
    }
}
