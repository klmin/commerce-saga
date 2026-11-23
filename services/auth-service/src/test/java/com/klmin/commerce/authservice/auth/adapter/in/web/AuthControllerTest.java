package com.klmin.commerce.authservice.auth.adapter.in.web;

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
import com.klmin.commerce.authservice.common.config.SecurityConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(controllers = {AuthController.class, JwksController.class}, properties = "auth.cookie.secure=true")
@Import(SecurityConfig.class)
class AuthControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RefreshCookie.class)
    static class CookieConfig {

        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }

    private static final String REFRESH = "0b0f7e6e-3f5c-4c8e-9a7e-2f6d8c1b4a11." + "a".repeat(43);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SignUpUseCase signUp;
    @MockitoBean
    LoginUseCase login;
    @MockitoBean
    RefreshTokenUseCase refresh;
    @MockitoBean
    LogoutUseCase logout;
    @MockitoBean
    ManageSessionsUseCase sessions;
    @MockitoBean
    GetJwksUseCase jwks;
    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void 가입하면_201_과_사용자_id() throws Exception {
        given(signUp.signUp(new SignUpCommand("alice", "password1"))).willReturn(7L);

        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"alice\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(7));
    }

    @Test
    void 가입_요청_값이_비면_400() throws Exception {
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{\"loginId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void 로그인하면_access_token_은_본문_refresh_token_은_httpOnly_쿠키() throws Exception {
        given(login.login(new LoginCommand("alice", "password1", "test-agent")))
                .willReturn(new TokenResult("access.jwt", Instant.now().plusSeconds(900), REFRESH, Duration.ofDays(14)));

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.USER_AGENT, "test-agent")
                        .content("{\"loginId\":\"alice\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("refresh_token=" + REFRESH), containsString("Path=/auth"),
                        containsString("Max-Age=1209600"), containsString("HttpOnly"), containsString("Secure"),
                        containsString("SameSite=Lax"))));
    }

    @Test
    void 로그인이_잠기면_429_와_Retry_After() throws Exception {
        given(login.login(any())).willThrow(new LoginLockedException(Duration.ofSeconds(90)));

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"alice\",\"password\":\"password1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "90"));
    }

    @Test
    void refresh_는_쿠키를_읽고_회전하면_새_쿠키_유예면_쿠키를_건드리지_않는다() throws Exception {
        given(refresh.refresh(REFRESH))
                .willReturn(new TokenResult("a1", Instant.now().plusSeconds(900), "next-token", Duration.ofDays(14)))
                .willReturn(new TokenResult("a2", Instant.now().plusSeconds(900), null, null));

        mvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", REFRESH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a1"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=next-token")));
        mvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", REFRESH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a2"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void refresh_실패는_401_Problem_Details() throws Exception {
        given(refresh.refresh(null)).willThrow(new InvalidRefreshTokenException());

        mvc.perform(post("/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("다시 로그인해 주세요."));
    }

    @Test
    void 로그아웃은_쿠키를_지운다() throws Exception {
        mvc.perform(post("/auth/logout").cookie(new Cookie("refresh_token", REFRESH)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("refresh_token=;"),
                        containsString("Max-Age=0"))));
        verify(logout).logout(REFRESH);
    }

    @Test
    void 기기_관리와_전체_로그아웃은_access_token_이_필요하다() throws Exception {
        mvc.perform(get("/auth/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/logout-all")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/auth/sessions/x")).andExpect(status().isUnauthorized());
    }

    @Test
    void 기기_목록과_전체_로그아웃의_대상은_토큰의_sub() throws Exception {
        Instant now = Instant.parse("2026-11-23T00:00:00Z");
        given(sessions.sessions(7L, REFRESH))
                .willReturn(List.of(new SessionResult("s1", "phone", now, now, now.plusSeconds(60), true)));

        mvc.perform(get("/auth/sessions").with(jwt().jwt(j -> j.subject("7"))).cookie(new Cookie("refresh_token", REFRESH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value("s1"))
                .andExpect(jsonPath("$[0].current").value(true));
        mvc.perform(post("/auth/logout-all").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        verify(logout).logoutAll(7L);
    }

    @Test
    void 남의_기기를_로그아웃시키려_하면_404() throws Exception {
        willThrow(new SessionNotFoundException()).given(sessions).revoke(7L, "someone-elses");

        mvc.perform(delete("/auth/sessions/someone-elses").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void JWKS_는_토큰_없이_캐시_가능하게() throws Exception {
        given(jwks.jwks()).willReturn(Map.of("keys", List.of(Map.of("kid", "k1", "kty", "RSA"))));

        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value("k1"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=300")));
    }
}
