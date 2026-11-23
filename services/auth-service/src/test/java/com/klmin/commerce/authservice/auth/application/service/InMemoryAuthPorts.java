package com.klmin.commerce.authservice.auth.application.service;

import com.klmin.commerce.authservice.auth.application.DuplicateLoginIdException;
import com.klmin.commerce.authservice.auth.application.port.out.AccessTokenPort;
import com.klmin.commerce.authservice.auth.application.port.out.AccessTokenResult;
import com.klmin.commerce.authservice.auth.application.port.out.LoginAttemptPort;
import com.klmin.commerce.authservice.auth.application.port.out.PasswordHasher;
import com.klmin.commerce.authservice.auth.application.port.out.RefreshFamilyPort;
import com.klmin.commerce.authservice.auth.application.port.out.RefreshTokenGenerator;
import com.klmin.commerce.authservice.auth.application.port.out.UserPort;
import com.klmin.commerce.authservice.auth.domain.RefreshFamily;
import com.klmin.commerce.authservice.auth.domain.RefreshToken;
import com.klmin.commerce.authservice.auth.domain.Role;
import com.klmin.commerce.authservice.auth.domain.User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        final Map<Long, User> byId = new HashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public User save(User user) {
            if (user.id() == null && findByLoginId(user.loginId()).isPresent()) {
                throw new DuplicateLoginIdException();
            }
            long id = user.id() != null ? user.id() : sequence.incrementAndGet();
            User saved = new User(id, user.loginId(), user.passwordHash(), user.roles(), user.status(),
                    user.createdAt(), user.updatedAt());
            byId.put(id, saved);
            return saved;
        }

        @Override
        public Optional<User> findByLoginId(String loginId) {
            return byId.values().stream().filter(u -> u.loginId().equals(loginId)).findFirst();
        }

        @Override
        public Optional<User> findById(long id) {
            return Optional.ofNullable(byId.get(id));
        }
    }

    /** 발행한 회원 이벤트를 기록한다 (outbox 대역) */
    static final class UserEvents implements com.klmin.commerce.authservice.auth.application.port.out.UserEventPort {
        final java.util.List<String> log = new java.util.ArrayList<>();

        @Override
        public void userRegistered(long userId, Instant registeredAt) {
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

    /** access token 값 = "at:<userId>:<roles>" */
    static final class AccessTokens implements AccessTokenPort {
        @Override
        public AccessTokenResult issue(long userId, Set<Role> roles, Instant now, Duration ttl) {
            return new AccessTokenResult("at:" + userId + ":" + roles.stream().map(Role::name).sorted().toList(),
                    now.plus(ttl));
        }

        @Override
        public Map<String, Object> publicJwks() {
            return Map.of("keys", List.of());
        }
    }

    static final class Generator implements RefreshTokenGenerator {
        private final AtomicInteger families = new AtomicInteger();
        private final AtomicInteger secrets = new AtomicInteger();

        @Override
        public RefreshToken newFamily() {
            return next("%08x-0000-4000-8000-000000000000".formatted(families.incrementAndGet()));
        }

        @Override
        public RefreshToken next(String familyId) {
            String n = Integer.toString(secrets.incrementAndGet());
            return new RefreshToken(familyId, "s".repeat(43 - n.length()) + n);
        }
    }

    static final class Families implements RefreshFamilyPort {
        final Map<String, RefreshFamily> store = new HashMap<>();
        /** replace 직전에 끼어드는 다른 요청 (동시 refresh 재현) */
        Runnable beforeReplace = () -> { };

        @Override
        public void create(RefreshFamily family) {
            store.put(family.familyId(), family);
        }

        @Override
        public Optional<RefreshFamily> find(String familyId) {
            return Optional.ofNullable(store.get(familyId));
        }

        @Override
        public boolean replace(RefreshFamily expected, RefreshFamily updated) {
            Runnable hook = beforeReplace;
            beforeReplace = () -> { };
            hook.run();
            RefreshFamily stored = store.get(expected.familyId());
            if (stored == null || !stored.currentHash().equals(expected.currentHash())) {
                return false;
            }
            store.put(updated.familyId(), updated);
            return true;
        }

        @Override
        public void delete(RefreshFamily family) {
            store.remove(family.familyId());
        }

        @Override
        public void deleteAll(long userId) {
            store.values().removeIf(f -> f.userId() == userId);
        }

        @Override
        public List<RefreshFamily> findAll(long userId, Instant now) {
            return new ArrayList<>(store.values().stream()
                    .filter(f -> f.userId() == userId && now.isBefore(f.expiresAt())).toList());
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
