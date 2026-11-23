package com.klmin.commerce.authservice;

import com.klmin.commerce.authservice.auth.application.InvalidRefreshTokenException;
import com.klmin.commerce.authservice.auth.application.port.in.LoginCommand;
import com.klmin.commerce.authservice.auth.application.port.in.LoginUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.RefreshTokenUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpCommand;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.TokenResult;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Postgres + Redis 를 띄워 가입 → 로그인 → refresh 회전 → 재사용 탐지 → 기기 관리까지 실제 저장소로 확인한다.
 * access token 은 JWKS 로 받은 공개키로 서명을 검증한다 (Gateway·서비스가 할 일과 같다).
 */
@SpringBootTest(properties = "auth.cookie.secure=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AuthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SignUpUseCase signUp;

    @Autowired
    LoginUseCase login;

    @Autowired
    RefreshTokenUseCase refresh;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcClient jdbc;

    private String loginId;

    @BeforeEach
    void setUp() {
        loginId = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void 로그인으로_받은_access_token_을_JWKS_공개키로_검증할_수_있다() throws Exception {
        long userId = signUp.signUp(new SignUpCommand(loginId, "password1"));
        assertThat(jdbc.sql("select password_hash from users where id = ?").param(userId).query(String.class).single())
                .startsWith("$2").doesNotContain("password1");

        MvcResult result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"%s\",\"password\":\"password1\"}".formatted(loginId)))
                .andExpect(status().isOk()).andReturn();
        String accessToken = json(result, "accessToken");

        String jwks = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JWKSet keys = JWKSet.parse(jwks);
        String kid = SignedJWT.parse(accessToken).getHeader().getKeyID();
        RSAKey key = (RSAKey) keys.getKeyByKeyId(kid);
        assertThat(key).isNotNull();
        assertThat(key.isPrivate()).isFalse();
        assertThat(jwks).doesNotContain("\"d\"");

        Jwt jwt = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(accessToken);
        assertThat(jwt.getSubject()).isEqualTo(Long.toString(userId));
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("commerce-saga-auth");
    }

    @Test
    void 쿠키로_refresh_하면_회전하고_옛_쿠키를_다시_쓰면_family_가_Redis_에서_지워진다() throws Exception {
        signUp.signUp(new SignUpCommand(loginId, "password1"));
        String first = refreshCookie(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"%s\",\"password\":\"password1\"}".formatted(loginId))).andReturn());
        String familyKey = "auth:family:" + first.substring(0, first.indexOf('.'));

        String second = refreshCookie(mvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", first)))
                .andExpect(status().isOk()).andReturn());
        assertThat(second).isNotEqualTo(first);
        assertThat(redis.hasKey(familyKey)).isTrue();
        // 서버에는 secret 원문이 없다
        assertThat(redis.opsForHash().values(familyKey)).noneMatch(v -> !((String) v).isEmpty() && second.endsWith((String) v));

        // 유예(20초)를 지난 것처럼 직전 secret 의 유예를 끝낸다
        redis.opsForHash().put(familyKey, "previousValidUntil", "0");
        mvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", first)))
                .andExpect(status().isUnauthorized());

        assertThat(redis.hasKey(familyKey)).isFalse();
        mvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", second)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_refresh_token_으로_동시에_8번_요청해도_회전은_한_번이고_family_는_살아_있다() throws Exception {
        signUp.signUp(new SignUpCommand(loginId, "password1"));
        TokenResult tokens = login.login(new LoginCommand(loginId, "password1", "test"));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TokenResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                Callable<TokenResult> task = () -> {
                    start.await();
                    return refresh.refresh(tokens.refreshToken());
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<TokenResult> results = new ArrayList<>();
            for (Future<TokenResult> f : futures) {
                results.add(f.get());
            }

            List<String> rotated = results.stream().map(TokenResult::refreshToken).filter(t -> t != null).toList();
            assertThat(rotated).hasSize(1);
            assertThat(results).allMatch(r -> r.accessToken() != null);
            assertThat(refresh.refresh(rotated.getFirst()).refreshToken()).isNotNull();
        }
    }

    @Test
    void 기기_목록과_다른_기기_로그아웃_전체_로그아웃() throws Exception {
        signUp.signUp(new SignUpCommand(loginId, "password1"));
        TokenResult phone = login.login(new LoginCommand(loginId, "password1", "phone"));
        TokenResult laptop = login.login(new LoginCommand(loginId, "password1", "laptop"));
        String other = "o" + loginId.substring(1);
        signUp.signUp(new SignUpCommand(other, "password1"));
        TokenResult otherPhone = login.login(new LoginCommand(other, "password1", "other-phone"));
        String bearer = "Bearer " + laptop.accessToken();

        String sessions = mvc.perform(get("/auth/sessions").header(HttpHeaders.AUTHORIZATION, bearer)
                        .cookie(new Cookie("refresh_token", laptop.refreshToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(sessions).contains("\"agent\":\"phone\"", "\"agent\":\"laptop\"").doesNotContain("other-phone");

        // 남의 기기는 404, 그대로 살아 있다
        mvc.perform(delete("/auth/sessions/" + familyId(otherPhone)).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
        assertThat(refresh.refresh(otherPhone.refreshToken()).refreshToken()).isNotNull();

        // 노트북에서 폰을 로그아웃
        mvc.perform(delete("/auth/sessions/" + familyId(phone)).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNoContent());
        assertThatThrownBy(() -> refresh.refresh(phone.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);

        mvc.perform(post("/auth/logout-all").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isNoContent());
        assertThatThrownBy(() -> refresh.refresh(laptop.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void 다섯_번_틀리면_맞는_비밀번호도_429() throws Exception {
        signUp.signUp(new SignUpCommand(loginId, "password1"));
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginId\":\"%s\",\"password\":\"wrong-pass\"}".formatted(loginId)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"%s\",\"password\":\"password1\"}".formatted(loginId)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void 같은_아이디로_가입하면_409() throws Exception {
        signUp.signUp(new SignUpCommand(loginId, "password1"));

        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"%s\",\"password\":\"password2\"}".formatted(loginId.toUpperCase())))
                .andExpect(status().isConflict());
    }

    private static String familyId(TokenResult tokens) {
        return tokens.refreshToken().substring(0, tokens.refreshToken().indexOf('.'));
    }

    private static String refreshCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("refresh_token=");
        return setCookie.substring("refresh_token=".length(), setCookie.indexOf(';'));
    }

    private static String json(MvcResult result, String field) throws Exception {
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"" + field + "\":\"") + field.length() + 4;
        return body.substring(start, body.indexOf('"', start));
    }
}
