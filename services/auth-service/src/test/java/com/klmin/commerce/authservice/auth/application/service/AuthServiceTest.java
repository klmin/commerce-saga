package com.klmin.commerce.authservice.auth.application.service;

import com.klmin.commerce.authservice.auth.application.DuplicateLoginIdException;
import com.klmin.commerce.authservice.auth.application.InvalidCredentialsException;
import com.klmin.commerce.authservice.auth.application.LoginLockedException;
import com.klmin.commerce.authservice.auth.application.port.in.AuthenticateCommand;
import com.klmin.commerce.authservice.auth.application.port.in.AuthenticatedUserResult;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpCommand;
import com.klmin.commerce.authservice.auth.domain.User;
import com.klmin.commerce.authservice.auth.domain.UserStatus;
import com.klmin.commerce.authservice.common.error.DomainValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 가입, SAS 로그인 폼의 자격 확인·실패 제한, 토큰 클레임 */
class AuthServiceTest {

    private static final AuthPolicy POLICY = new AuthPolicy(5, Duration.ofMinutes(15));

    private InMemoryAuthPorts.MutableClock clock;
    private InMemoryAuthPorts.Users users;
    private InMemoryAuthPorts.Hasher hasher;
    private InMemoryAuthPorts.UserEvents events;
    private AuthService service;

    @BeforeEach
    void setUp() {
        clock = new InMemoryAuthPorts.MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
        users = new InMemoryAuthPorts.Users();
        hasher = new InMemoryAuthPorts.Hasher();
        events = new InMemoryAuthPorts.UserEvents();
        service = new AuthService(users, hasher, new InMemoryAuthPorts.LoginAttempts(clock), events, POLICY, clock);
    }

    private AuthenticatedUserResult login(String loginId, String password) {
        return service.authenticate(new AuthenticateCommand(loginId, password));
    }

    @Test
    void 가입하면_비밀번호는_해시로_저장되고_같은_아이디는_대소문자_달라도_거부() {
        long id = service.signUp(new SignUpCommand("Alice", "password1"));

        assertThat(users.findById(id).orElseThrow().passwordHash()).isEqualTo("hashed:password1");
        assertThatThrownBy(() -> service.signUp(new SignUpCommand("ALICE", "password2")))
                .isInstanceOf(DuplicateLoginIdException.class);
        assertThatThrownBy(() -> service.signUp(new SignUpCommand("bobby", "short")))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void 가입에_성공했을_때만_UserRegistered_를_한_번_남긴다() {
        long id = service.signUp(new SignUpCommand("carol", "password1"));

        assertThatThrownBy(() -> service.signUp(new SignUpCommand("carol", "password2")))
                .isInstanceOf(DuplicateLoginIdException.class);
        assertThatThrownBy(() -> service.signUp(new SignUpCommand("dave", "short")))
                .isInstanceOf(DomainValidationException.class);
        assertThat(events.log).containsExactly("UserRegistered:" + id + "@2026-11-23T00:00:00Z");
    }

    @Test
    void 로그인_확인은_토큰을_만들지_않고_userId_와_역할만_돌려준다() {
        long id = service.signUp(new SignUpCommand("alice", "password1"));

        AuthenticatedUserResult user = login("ALICE", "password1");

        assertThat(user.userId()).isEqualTo(id);
        assertThat(user.roles()).containsExactly("USER");
    }

    @Test
    void 없는_아이디와_틀린_비밀번호는_같은_예외이고_없는_아이디도_해시_비교_시간을_쓴다() {
        service.signUp(new SignUpCommand("alice", "password1"));

        assertThatThrownBy(() -> login("alice", "wrong-pass")).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> login("nobody", "password1")).isInstanceOf(InvalidCredentialsException.class);
        assertThat(hasher.dummyMatches).isEqualTo(1);
    }

    @Test
    void 정지된_계정은_비밀번호가_맞아도_로그인_실패() {
        long id = service.signUp(new SignUpCommand("alice", "password1"));
        suspend(id);

        assertThatThrownBy(() -> login("alice", "password1")).isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void 다섯_번_틀리면_잠겨_맞는_비밀번호도_거부하고_잠금이_풀리면_다시_로그인() {
        service.signUp(new SignUpCommand("alice", "password1"));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> login("alice", "wrong-pass")).isInstanceOf(InvalidCredentialsException.class);
            clock.advance(Duration.ofSeconds(10));
        }

        assertThatThrownBy(() -> login("alice", "password1"))
                .isInstanceOf(LoginLockedException.class)
                .satisfies(e -> assertThat(((LoginLockedException) e).retryAfter())
                        .isEqualTo(Duration.ofMinutes(15).minusSeconds(50)));

        clock.advance(Duration.ofMinutes(15));
        assertThat(login("alice", "password1").roles()).containsExactly("USER");
    }

    @Test
    void 성공하면_실패_횟수가_초기화된다() {
        service.signUp(new SignUpCommand("alice", "password1"));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> login("alice", "wrong-pass")).isInstanceOf(InvalidCredentialsException.class);
        }
        login("alice", "password1");
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> login("alice", "wrong-pass")).isInstanceOf(InvalidCredentialsException.class);
        }

        assertThat(login("alice", "password1").roles()).containsExactly("USER");
    }

    @Test
    void 토큰_클레임은_지금_역할을_읽고_정지된_사용자는_거절한다() {
        long id = service.signUp(new SignUpCommand("alice", "password1"));
        assertThat(service.currentClaims(id).roles()).containsExactly("USER");

        suspend(id);
        assertThatThrownBy(() -> service.currentClaims(id)).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.currentClaims(999_999)).isInstanceOf(InvalidCredentialsException.class);
    }

    private void suspend(long id) {
        User user = users.findById(id).orElseThrow();
        users.save(new User(id, user.loginId(), user.passwordHash(), user.roles(), UserStatus.SUSPENDED,
                user.createdAt(), user.updatedAt()));
    }
}
