package com.klmin.commerce.authservice.auth.adapter.in.web;

import com.klmin.commerce.authservice.auth.application.port.in.LoginCommand;
import com.klmin.commerce.authservice.auth.application.port.in.LoginUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.LogoutUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.ManageSessionsUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.RefreshTokenUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.SessionResult;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpCommand;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.TokenResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 회원가입·로그인·refresh·로그아웃·로그인 기기 관리 (ADR 0008).
 * access token 은 응답 본문, refresh token 은 httpOnly 쿠키로만 준다. 기기 관리·전체 로그아웃은 access token 이 필요하고,
 * 대상 사용자는 토큰의 {@code sub} 다 (경로·본문으로 받지 않는다).
 */
@RestController
@RequestMapping("/auth")
class AuthController {

    private final SignUpUseCase signUp;
    private final LoginUseCase login;
    private final RefreshTokenUseCase refresh;
    private final LogoutUseCase logout;
    private final ManageSessionsUseCase sessions;
    private final RefreshCookie cookie;
    private final Clock clock;

    AuthController(SignUpUseCase signUp, LoginUseCase login, RefreshTokenUseCase refresh, LogoutUseCase logout,
                   ManageSessionsUseCase sessions, RefreshCookie cookie, Clock clock) {
        this.signUp = signUp;
        this.login = login;
        this.refresh = refresh;
        this.logout = logout;
        this.sessions = sessions;
        this.cookie = cookie;
        this.clock = clock;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    SignUpResponse signUp(@Valid @RequestBody SignUpRequest request) {
        return new SignUpResponse(signUp.signUp(new SignUpCommand(request.loginId(), request.password())));
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                        @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String agent) {
        return withTokens(login.login(new LoginCommand(request.loginId(), request.password(), agent)));
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(@CookieValue(value = RefreshCookie.NAME, required = false) String refreshToken) {
        return withTokens(refresh.refresh(refreshToken));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(value = RefreshCookie.NAME, required = false) String refreshToken) {
        logout.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.clear()).build();
    }

    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt jwt) {
        logout.logoutAll(userId(jwt));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.clear()).build();
    }

    @GetMapping("/sessions")
    List<SessionResponse> sessions(@AuthenticationPrincipal Jwt jwt,
                                   @CookieValue(value = RefreshCookie.NAME, required = false) String refreshToken) {
        return sessions.sessions(userId(jwt), refreshToken).stream().map(SessionResponse::from).toList();
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        sessions.revoke(userId(jwt), sessionId);
    }

    // 회전하지 않은 응답(동시 refresh 유예)은 쿠키를 그대로 둔다. 먼저 온 요청이 이미 새 쿠키를 심었다
    private ResponseEntity<TokenResponse> withTokens(TokenResult result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.refreshToken() != null) {
            response.header(HttpHeaders.SET_COOKIE, cookie.issue(result.refreshToken(), result.refreshTokenMaxAge()));
        }
        return response.body(TokenResponse.from(result, clock.instant()));
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    record SignUpRequest(
            @NotBlank @Size(max = 30) String loginId,
            @NotBlank @Size(max = 64) String password) {

        @Override
        public String toString() {
            return "SignUpRequest[loginId=" + loginId + ", password=***]";
        }
    }

    record SignUpResponse(long userId) {
    }

    record LoginRequest(
            @NotBlank @Size(max = 100) String loginId,
            @NotBlank @Size(max = 200) String password) {

        @Override
        public String toString() {
            return "LoginRequest[loginId=" + loginId + ", password=***]";
        }
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt) {

        static TokenResponse from(TokenResult result, Instant now) {
            long expiresIn = Math.max(0, Duration.between(now, result.accessTokenExpiresAt()).toSeconds());
            return new TokenResponse(result.accessToken(), "Bearer", expiresIn, result.accessTokenExpiresAt());
        }
    }

    record SessionResponse(String sessionId, String agent, Instant createdAt, Instant lastUsedAt, Instant expiresAt,
                           boolean current) {

        static SessionResponse from(SessionResult s) {
            return new SessionResponse(s.sessionId(), s.agent(), s.createdAt(), s.lastUsedAt(), s.expiresAt(), s.current());
        }
    }
}
