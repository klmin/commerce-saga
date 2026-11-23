package com.klmin.commerce.authservice.auth.application.service;

import com.klmin.commerce.authservice.auth.application.DuplicateLoginIdException;
import com.klmin.commerce.authservice.auth.application.InvalidCredentialsException;
import com.klmin.commerce.authservice.auth.application.InvalidRefreshTokenException;
import com.klmin.commerce.authservice.auth.application.LoginLockedException;
import com.klmin.commerce.authservice.auth.application.SessionNotFoundException;
import com.klmin.commerce.authservice.auth.application.port.in.LoginCommand;
import com.klmin.commerce.authservice.auth.application.port.in.SessionResult;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpCommand;
import com.klmin.commerce.authservice.auth.application.port.in.TokenResult;
import com.klmin.commerce.authservice.auth.domain.RefreshToken;
import com.klmin.commerce.authservice.auth.domain.Role;
import com.klmin.commerce.authservice.auth.domain.User;
import com.klmin.commerce.authservice.auth.domain.UserStatus;
import com.klmin.commerce.authservice.common.error.DomainValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    private static final AuthPolicy POLICY = new AuthPolicy(Duration.ofMinutes(15), Duration.ofDays(14),
            Duration.ofDays(30), Duration.ofSeconds(20), 5, Duration.ofMinutes(15));

    private InMemoryAuthPorts.MutableClock clock;
    private InMemoryAuthPorts.Users users;
    private InMemoryAuthPorts.Hasher hasher;
    private InMemoryAuthPorts.Families families;
    private AuthService service;

    @BeforeEach
    void setUp() {
        clock = new InMemoryAuthPorts.MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
        users = new InMemoryAuthPorts.Users();
        hasher = new InMemoryAuthPorts.Hasher();
        families = new InMemoryAuthPorts.Families();
        service = new AuthService(users, hasher, new InMemoryAuthPorts.AccessTokens(), new InMemoryAuthPorts.Generator(),
                families, new InMemoryAuthPorts.LoginAttempts(clock), POLICY, clock);
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
    void 로그인하면_access_token_과_새_family_의_refresh_token() {
        long id = service.signUp(new SignUpCommand("alice", "password1"));

        TokenResult tokens = service.login(new LoginCommand("Alice", "password1", "Firefox"));

        assertThat(tokens.accessToken()).isEqualTo("at:" + id + ":[USER]");
        assertThat(tokens.accessTokenExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(tokens.refreshTokenMaxAge()).isEqualTo(Duration.ofDays(14));
        RefreshToken refresh = RefreshToken.parse(tokens.refreshToken()).orElseThrow();
        assertThat(families.find(refresh.familyId()).orElseThrow().agent()).isEqualTo("Firefox");
    }

    @Test
    void 여러_기기에서_로그인하면_family_가_따로_생기고_서로_건드리지_않는다() {
        service.signUp(new SignUpCommand("alice", "password1"));

        TokenResult phone = service.login(new LoginCommand("alice", "password1", "phone"));
        TokenResult laptop = service.login(new LoginCommand("alice", "password1", "laptop"));

        assertThat(families.store).hasSize(2);
        assertThat(service.refresh(phone.refreshToken()).refreshToken()).isNotNull();
        assertThat(service.refresh(laptop.refreshToken()).refreshToken()).isNotNull();
    }

    @Test
    void 없는_아이디와_틀린_비밀번호는_같은_예외이고_없는_아이디도_해시_비교_시간을_쓴다() {
        service.signUp(new SignUpCommand("alice", "password1"));

        assertThatThrownBy(() -> service.login(new LoginCommand("alice", "wrong-pass", null)))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.login(new LoginCommand("nobody", "password1", null)))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(hasher.dummyMatches).isEqualTo(1);
        assertThat(families.store).isEmpty();
    }

    @Test
    void 정지된_계정은_비밀번호가_맞아도_로그인_실패() {
        long id = service.signUp(new SignUpCommand("alice", "password1"));
        suspend(id);

        assertThatThrownBy(() -> service.login(new LoginCommand("alice", "password1", null)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void 다섯_번_틀리면_잠겨_맞는_비밀번호도_거부하고_잠금이_풀리면_다시_로그인() {
        service.signUp(new SignUpCommand("alice", "password1"));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login(new LoginCommand("alice", "wrong-pass", null)))
                    .isInstanceOf(InvalidCredentialsException.class);
            clock.advance(Duration.ofSeconds(10));
        }

        assertThatThrownBy(() -> service.login(new LoginCommand("alice", "password1", null)))
                .isInstanceOf(LoginLockedException.class)
                .satisfies(e -> assertThat(((LoginLockedException) e).retryAfter())
                        .isEqualTo(Duration.ofMinutes(15).minusSeconds(50)));

        clock.advance(Duration.ofMinutes(15));
        assertThat(service.login(new LoginCommand("alice", "password1", null)).accessToken()).isNotNull();
    }

    @Test
    void 성공하면_실패_횟수가_초기화된다() {
        service.signUp(new SignUpCommand("alice", "password1"));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.login(new LoginCommand("alice", "wrong-pass", null)))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        service.login(new LoginCommand("alice", "password1", null));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.login(new LoginCommand("alice", "wrong-pass", null)))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThat(service.login(new LoginCommand("alice", "password1", null)).accessToken()).isNotNull();
    }

    @Test
    void refresh_하면_새_refresh_token_으로_회전하고_옛_토큰을_유예_뒤에_다시_쓰면_family_를_지운다() {
        service.signUp(new SignUpCommand("alice", "password1"));
        TokenResult login = service.login(new LoginCommand("alice", "password1", null));

        clock.advance(Duration.ofMinutes(10));
        TokenResult refreshed = service.refresh(login.refreshToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken())
                .startsWith(RefreshToken.parse(login.refreshToken()).orElseThrow().familyId() + ".");

        clock.advance(Duration.ofMinutes(1));
        // 탈취자가 옛 토큰을 쓴다 → 탐지, family 폐기
        assertThatThrownBy(() -> service.refresh(login.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(families.store).isEmpty();
        // 정상 사용자의 새 토큰도 함께 무효 → 다시 로그인해야 한다
        assertThatThrownBy(() -> service.refresh(refreshed.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void 회전_직후_유예_안에_직전_토큰이_오면_access_token_만_주고_family_는_그대로() {
        service.signUp(new SignUpCommand("alice", "password1"));
        TokenResult login = service.login(new LoginCommand("alice", "password1", null));
        TokenResult first = service.refresh(login.refreshToken());

        clock.advance(Duration.ofSeconds(5));
        TokenResult second = service.refresh(login.refreshToken());

        assertThat(second.refreshToken()).isNull();
        assertThat(second.accessToken()).isNotNull();
        assertThat(service.refresh(first.refreshToken()).refreshToken()).isNotNull();
    }

    @Test
    void 같은_토큰으로_동시에_회전하면_늦은_쪽은_다시_읽어_GRACE_로_끝난다() {
        service.signUp(new SignUpCommand("alice", "password1"));
        TokenResult login = service.login(new LoginCommand("alice", "password1", null));
        AtomicReference<TokenResult> concurrent = new AtomicReference<>();
        // 이 요청이 저장하기 직전에 다른 탭의 refresh 가 먼저 끝난다
        families.beforeReplace = () -> concurrent.set(service.refresh(login.refreshToken()));

        TokenResult late = service.refresh(login.refreshToken());

        assertThat(concurrent.get().refreshToken()).isNotNull();
        assertThat(late.refreshToken()).isNull();
        assertThat(late.accessToken()).isNotNull();
        assertThat(service.refresh(concurrent.get().refreshToken()).refreshToken()).isNotNull();
    }

    @Test
    void refresh_할_때_역할을_DB_에서_다시_읽고_정지된_사용자는_family_를_지운다() {
        long id = service.signUp(new SignUpCommand("alice", "password1"));
        TokenResult login = service.login(new LoginCommand("alice", "password1", null));
        User user = users.findById(id).orElseThrow();
        users.save(new User(id, user.loginId(), user.passwordHash(), EnumSet.of(Role.USER, Role.ADMIN), user.status(),
                user.createdAt(), user.updatedAt()));

        TokenResult refreshed = service.refresh(login.refreshToken());
        assertThat(refreshed.accessToken()).isEqualTo("at:" + id + ":[ADMIN, USER]");

        suspend(id);
        assertThatThrownBy(() -> service.refresh(refreshed.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(families.store).isEmpty();
    }

    @Test
    void 형식이_틀리거나_없는_family_나_만료된_토큰은_거부() {
        service.signUp(new SignUpCommand("alice", "password1"));
        TokenResult login = service.login(new LoginCommand("alice", "password1", null));

        assertThatThrownBy(() -> service.refresh(null)).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.refresh("garbage")).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.refresh("00000000-0000-4000-8000-000000000000." + "x".repeat(43)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        clock.advance(Duration.ofDays(14));
        assertThatThrownBy(() -> service.refresh(login.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(families.store).isEmpty();
    }

    @Test
    void 로그아웃은_그_기기만_전체_로그아웃은_모든_기기() {
        long id = service.signUp(new SignUpCommand("alice", "password1"));
        TokenResult phone = service.login(new LoginCommand("alice", "password1", "phone"));
        TokenResult laptop = service.login(new LoginCommand("alice", "password1", "laptop"));
        TokenResult tablet = service.login(new LoginCommand("alice", "password1", "tablet"));

        service.logout(phone.refreshToken());
        service.logout(phone.refreshToken()); // 두 번 해도 조용히 끝난다
        service.logout(null);
        assertThatThrownBy(() -> service.refresh(phone.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(service.refresh(laptop.refreshToken()).refreshToken()).isNotNull();

        service.logoutAll(id);
        assertThat(families.store).isEmpty();
        assertThatThrownBy(() -> service.refresh(tablet.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void 기기_목록은_내_것만_최근_사용순이고_지금_기기를_표시한다() {
        long alice = service.signUp(new SignUpCommand("alice", "password1"));
        service.signUp(new SignUpCommand("bobby", "password1"));
        TokenResult phone = service.login(new LoginCommand("alice", "password1", "phone"));
        clock.advance(Duration.ofMinutes(1));
        TokenResult laptop = service.login(new LoginCommand("alice", "password1", "laptop"));
        service.login(new LoginCommand("bobby", "password1", "bob-phone"));

        List<SessionResult> sessions = service.sessions(alice, phone.refreshToken());

        assertThat(sessions).extracting(SessionResult::agent).containsExactly("laptop", "phone");
        assertThat(sessions).extracting(SessionResult::current).containsExactly(false, true);
        assertThat(service.sessions(alice, null)).noneMatch(SessionResult::current);
        assertThat(laptop.refreshToken()).isNotNull();
    }

    @Test
    void 다른_사용자의_기기는_로그아웃시킬_수_없다() {
        long alice = service.signUp(new SignUpCommand("alice", "password1"));
        long bob = service.signUp(new SignUpCommand("bobby", "password1"));
        TokenResult bobPhone = service.login(new LoginCommand("bobby", "password1", "bob-phone"));
        String bobSession = RefreshToken.parse(bobPhone.refreshToken()).orElseThrow().familyId();

        assertThatThrownBy(() -> service.revoke(alice, bobSession)).isInstanceOf(SessionNotFoundException.class);
        assertThat(service.refresh(bobPhone.refreshToken()).refreshToken()).isNotNull();

        service.revoke(bob, bobSession);
        assertThat(families.store).isEmpty();
    }

    private void suspend(long id) {
        User user = users.findById(id).orElseThrow();
        users.save(new User(id, user.loginId(), user.passwordHash(), user.roles(), UserStatus.SUSPENDED,
                user.createdAt(), user.updatedAt()));
    }
}
