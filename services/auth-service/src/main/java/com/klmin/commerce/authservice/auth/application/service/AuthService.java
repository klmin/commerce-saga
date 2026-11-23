package com.klmin.commerce.authservice.auth.application.service;

import com.klmin.commerce.authservice.auth.application.DuplicateLoginIdException;
import com.klmin.commerce.authservice.auth.application.InvalidCredentialsException;
import com.klmin.commerce.authservice.auth.application.InvalidRefreshTokenException;
import com.klmin.commerce.authservice.auth.application.LoginLockedException;
import com.klmin.commerce.authservice.auth.application.SessionNotFoundException;
import com.klmin.commerce.authservice.auth.application.port.in.GetJwksUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.LoginCommand;
import com.klmin.commerce.authservice.auth.application.port.in.LoginUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.LogoutUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.ManageSessionsUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.RefreshTokenUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.SessionResult;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpCommand;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.TokenResult;
import com.klmin.commerce.authservice.auth.application.port.out.AccessTokenPort;
import com.klmin.commerce.authservice.auth.application.port.out.AccessTokenResult;
import com.klmin.commerce.authservice.auth.application.port.out.LoginAttemptPort;
import com.klmin.commerce.authservice.auth.application.port.out.PasswordHasher;
import com.klmin.commerce.authservice.auth.application.port.out.RefreshFamilyPort;
import com.klmin.commerce.authservice.auth.application.port.out.RefreshTokenGenerator;
import com.klmin.commerce.authservice.auth.application.port.out.UserEventPort;
import com.klmin.commerce.authservice.auth.application.port.out.UserPort;
import com.klmin.commerce.authservice.auth.domain.PasswordPolicy;
import com.klmin.commerce.authservice.auth.domain.RefreshFamily;
import com.klmin.commerce.authservice.auth.domain.RefreshToken;
import com.klmin.commerce.authservice.auth.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 회원가입·로그인·refresh token rotation·로그아웃 (ADR 0008).
 * 회원은 DB, refresh token family 와 로그인 실패 횟수는 Redis 에 둔다.
 */
@Service
public class AuthService implements SignUpUseCase, LoginUseCase, RefreshTokenUseCase, LogoutUseCase,
        ManageSessionsUseCase, GetJwksUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_AGENT_LENGTH = 200;

    private final UserPort users;
    private final PasswordHasher passwordHasher;
    private final AccessTokenPort accessTokens;
    private final RefreshTokenGenerator refreshTokens;
    private final RefreshFamilyPort families;
    private final LoginAttemptPort loginAttempts;
    private final UserEventPort userEvents;
    private final AuthPolicy policy;
    private final Clock clock;

    public AuthService(UserPort users, PasswordHasher passwordHasher, AccessTokenPort accessTokens,
                       RefreshTokenGenerator refreshTokens, RefreshFamilyPort families, LoginAttemptPort loginAttempts,
                       UserEventPort userEvents, AuthPolicy policy, Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.families = families;
        this.loginAttempts = loginAttempts;
        this.userEvents = userEvents;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public long signUp(SignUpCommand command) {
        PasswordPolicy.validate(command.password());
        User user = User.register(command.loginId(), passwordHasher.hash(command.password()), clock.instant());
        if (users.findByLoginId(user.loginId()).isPresent()) {
            throw new DuplicateLoginIdException();
        }
        long userId = users.save(user).id();
        // 사용자 저장 + UserRegistered outbox = 한 트랜잭션. 가입이 롤백되면 이벤트도 없다 (point 가 계정을 만든다)
        userEvents.userRegistered(userId, user.createdAt());
        return userId;
    }

    /**
     * 없는 아이디·틀린 비밀번호·정지 계정을 응답으로 구분하지 않는다. 실패는 아이디별로 세고
     * {@code loginMaxFailures} 번이 되면 첫 실패부터 {@code loginLock} 동안 맞는 비밀번호도 거부한다.
     * 로그인할 때마다 새 family(기기 하나)를 만들고 다른 기기의 family 는 건드리지 않는다.
     */
    // 트랜잭션 없이: 조회 한 번뿐이고, 비밀번호 해시 비교·Redis 동안 DB 연결을 쥐고 있지 않게
    @Override
    public TokenResult login(LoginCommand command) {
        String loginId = User.normalizeLoginId(command.loginId());
        Optional<Duration> locked = loginAttempts.lockedFor(loginId, policy.loginMaxFailures());
        if (locked.isPresent()) {
            throw new LoginLockedException(locked.get());
        }
        Optional<User> found = users.findByLoginId(loginId);
        boolean passwordMatches;
        if (found.isPresent()) {
            passwordMatches = passwordHasher.matches(command.password(), found.get().passwordHash());
        } else {
            passwordHasher.matchAgainstDummy(command.password());
            passwordMatches = false;
        }
        if (!passwordMatches || !found.get().isActive()) {
            loginAttempts.recordFailure(loginId, policy.loginLock());
            throw new InvalidCredentialsException();
        }
        loginAttempts.reset(loginId);

        User user = found.get();
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.newFamily();
        RefreshFamily family = RefreshFamily.start(token, user.id(), agent(command.agent()), now,
                policy.refreshTokenTtl(), policy.refreshAbsoluteTtl());
        families.create(family);
        return tokens(user, now, token, family.remainingLifetime(now));
    }

    /**
     * refresh token rotation. 현재 secret 이면 새 secret 으로 바꾸고, 유예 안의 직전 secret 이면(동시 refresh)
     * access token 만 다시 준다. 그 밖의 secret(이미 회전된 옛 토큰)은 탈취로 보고 family 를 지운다.
     * 역할은 매번 DB 에서 다시 읽어, 역할 변경·계정 정지가 access token 수명 안에 반영되게 한다.
     */
    @Override
    public TokenResult refresh(String rawRefreshToken) {
        RefreshToken presented = RefreshToken.parse(rawRefreshToken).orElseThrow(InvalidRefreshTokenException::new);
        // 같은 토큰으로 동시에 회전하면 한쪽은 replace 에 실패한다. 다시 읽으면 직전 secret 이 되어 GRACE 로 끝난다
        for (int attempt = 0; attempt < 2; attempt++) {
            RefreshFamily family = families.find(presented.familyId()).orElseThrow(InvalidRefreshTokenException::new);
            Instant now = clock.instant();
            switch (family.decide(presented, now)) {
                case EXPIRED -> {
                    families.delete(family);
                    throw new InvalidRefreshTokenException();
                }
                case REUSE_DETECTED -> {
                    // familyId 는 refresh token 의 일부라 남기지 않는다. userId 는 서버에 저장된 값
                    log.warn("이미 회전된 refresh token 재사용, 그 기기 세션 폐기 (userId={})", family.userId());
                    families.delete(family);
                    throw new InvalidRefreshTokenException();
                }
                case GRACE -> {
                    User user = activeUserOf(family);
                    AccessTokenResult access = accessTokens.issue(user.id(), user.roles(), now, policy.accessTokenTtl());
                    return new TokenResult(access.value(), access.expiresAt(), null, null);
                }
                case ROTATE -> {
                    User user = activeUserOf(family);
                    RefreshToken next = refreshTokens.next(family.familyId());
                    RefreshFamily rotated = family.rotate(next, now, policy.refreshTokenTtl(), policy.refreshGrace());
                    if (families.replace(family, rotated)) {
                        return tokens(user, now, next, rotated.remainingLifetime(now));
                    }
                }
            }
        }
        throw new InvalidRefreshTokenException();
    }

    // 지워졌거나 정지된 사용자의 family 는 더 쓰지 못하게 지운다
    private User activeUserOf(RefreshFamily family) {
        Optional<User> user = users.findById(family.userId()).filter(User::isActive);
        if (user.isEmpty()) {
            families.delete(family);
            throw new InvalidRefreshTokenException();
        }
        return user.get();
    }

    @Override
    public void logout(String rawRefreshToken) {
        RefreshToken.parse(rawRefreshToken)
                .flatMap(token -> families.find(token.familyId()))
                .ifPresent(families::delete);
    }

    @Override
    public void logoutAll(long userId) {
        families.deleteAll(userId);
    }

    @Override
    public List<SessionResult> sessions(long userId, String rawCurrentRefreshToken) {
        String currentFamilyId = RefreshToken.parse(rawCurrentRefreshToken).map(RefreshToken::familyId).orElse(null);
        return families.findAll(userId, clock.instant()).stream()
                .sorted(Comparator.comparing(RefreshFamily::lastUsedAt).reversed())
                .map(f -> new SessionResult(f.familyId(), f.agent(), f.createdAt(), f.lastUsedAt(), f.expiresAt(),
                        f.familyId().equals(currentFamilyId)))
                .toList();
    }

    // 소유권 검사: 남의 family 는 없는 것과 같게 다룬다 (IDOR)
    @Override
    public void revoke(long userId, String sessionId) {
        RefreshFamily family = families.find(sessionId)
                .filter(f -> f.userId() == userId)
                .orElseThrow(SessionNotFoundException::new);
        families.delete(family);
    }

    @Override
    public Map<String, Object> jwks() {
        return accessTokens.publicJwks();
    }

    private TokenResult tokens(User user, Instant now, RefreshToken refreshToken, Duration refreshMaxAge) {
        AccessTokenResult access = accessTokens.issue(user.id(), user.roles(), now, policy.accessTokenTtl());
        return new TokenResult(access.value(), access.expiresAt(), refreshToken.value(), refreshMaxAge);
    }

    private static String agent(String agent) {
        if (agent == null) {
            return "";
        }
        return agent.length() <= MAX_AGENT_LENGTH ? agent : agent.substring(0, MAX_AGENT_LENGTH);
    }
}
