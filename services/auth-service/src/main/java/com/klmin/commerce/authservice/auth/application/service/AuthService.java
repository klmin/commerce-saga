package com.klmin.commerce.authservice.auth.application.service;

import com.klmin.commerce.authservice.auth.application.DuplicateLoginIdException;
import com.klmin.commerce.authservice.auth.application.InvalidCredentialsException;
import com.klmin.commerce.authservice.auth.application.LoginLockedException;
import com.klmin.commerce.authservice.auth.application.port.in.AuthenticateCommand;
import com.klmin.commerce.authservice.auth.application.port.in.AuthenticateUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.AuthenticatedUserResult;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpCommand;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.TokenClaimsUseCase;
import com.klmin.commerce.authservice.auth.application.port.out.LoginAttemptPort;
import com.klmin.commerce.authservice.auth.application.port.out.PasswordHasher;
import com.klmin.commerce.authservice.auth.application.port.out.UserEventPort;
import com.klmin.commerce.authservice.auth.application.port.out.UserPort;
import com.klmin.commerce.authservice.auth.domain.PasswordPolicy;
import com.klmin.commerce.authservice.auth.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 회원가입, SAS 로그인 폼의 자격 확인(실패 제한), 토큰 클레임 (ADR 0017). 토큰 발급·갱신·로그아웃은 SAS 가 한다.
 * 회원은 DB, 로그인 실패 횟수는 Redis 에 둔다.
 */
@Service
public class AuthService implements SignUpUseCase, AuthenticateUseCase, TokenClaimsUseCase {

    private final UserPort users;
    private final PasswordHasher passwordHasher;
    private final LoginAttemptPort loginAttempts;
    private final UserEventPort userEvents;
    private final AuthPolicy policy;
    private final Clock clock;

    public AuthService(UserPort users, PasswordHasher passwordHasher, LoginAttemptPort loginAttempts,
                       UserEventPort userEvents, AuthPolicy policy, Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
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
     * SAS 로그인 폼. 없는 아이디·틀린 비밀번호·정지 계정을 응답으로 구분하지 않는다. 실패는 아이디별로 세고
     * {@code loginMaxFailures} 번이 되면 첫 실패부터 {@code loginLock} 동안 맞는 비밀번호도 거부한다
     */
    // 트랜잭션 없이: 조회 한 번뿐이고, 비밀번호 해시 비교·Redis 동안 DB 연결을 쥐고 있지 않게
    @Override
    public AuthenticatedUserResult authenticate(AuthenticateCommand command) {
        return claimsOf(verifyCredentials(command.loginId(), command.password()));
    }

    @Override
    public AuthenticatedUserResult currentClaims(long userId) {
        return claimsOf(users.findById(userId).filter(User::isActive).orElseThrow(InvalidCredentialsException::new));
    }

    private static AuthenticatedUserResult claimsOf(User user) {
        return new AuthenticatedUserResult(user.id(),
                user.roles().stream().map(Enum::name).collect(Collectors.toSet()));
    }

    private User verifyCredentials(String rawLoginId, String password) {
        String loginId = User.normalizeLoginId(rawLoginId);
        Optional<Duration> locked = loginAttempts.lockedFor(loginId, policy.loginMaxFailures());
        if (locked.isPresent()) {
            throw new LoginLockedException(locked.get());
        }
        Optional<User> found = users.findByLoginId(loginId);
        boolean passwordMatches;
        if (found.isPresent()) {
            passwordMatches = passwordHasher.matches(password, found.get().passwordHash());
        } else {
            passwordHasher.matchAgainstDummy(password);
            passwordMatches = false;
        }
        if (!passwordMatches || !found.get().isActive()) {
            loginAttempts.recordFailure(loginId, policy.loginLock());
            throw new InvalidCredentialsException();
        }
        loginAttempts.reset(loginId);
        return found.get();
    }
}
