package com.klmin.commerce.authservice;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpCommand;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpUseCase;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SAS 인가 코드 흐름을 실제 저장소(Postgres + Redis)로 끝까지 확인한다 (후속 5-1, ADR 0017).
 * Gateway(BFF)가 할 일을 테스트가 대신한다: 인가 요청(PKCE) → 로그인 화면 → 폼 로그인 → 인가 코드 → 토큰 교환 → refresh.
 */
// AuthIntegrationTest 와 같은 설정이라 스프링 컨텍스트(컨테이너 포함)를 같이 쓴다
@SpringBootTest(properties = {"auth.cookie.secure=false", "outbox.relay.fixed-delay=1h", "auth.jwt.dev-key-file=",
        "auth.oauth.secret-dev-dir=", "auth.oauth.clients.gateway-web.secret=test-web-secret",
        "auth.oauth.clients.gateway-admin.secret=test-admin-secret"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class OAuthFlowIntegrationTest {

    static final String ISSUER = "http://localhost:5175";
    static final String CLIENT = "gateway-web";
    static final String SECRET = "test-web-secret";
    static final String REDIRECT = "http://localhost:5173/login/oauth2/code/gateway-web";
    static final String SESSION = "AUTH_SESSION";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired
    MockMvc mvc;

    @Autowired
    SignUpUseCase signUp;

    @Autowired
    JdbcClient jdbc;

    private String loginId;
    private long userId;

    @BeforeEach
    void setUp() {
        loginId = "o" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        userId = signUp.signUp(new SignUpCommand(loginId, "password1"));
    }

    @Test
    void discovery_는_설정한_issuer_와_OIDC_로그아웃_주소를_알린다() throws Exception {
        JsonNode doc = json(mvc.perform(get("/.well-known/openid-configuration")).andExpect(status().isOk()).andReturn());
        assertThat(doc.get("issuer").asString()).isEqualTo(ISSUER);
        assertThat(doc.get("authorization_endpoint").asString()).isEqualTo(ISSUER + "/oauth2/authorize");
        assertThat(doc.get("jwks_uri").asString()).isEqualTo(ISSUER + "/oauth2/jwks");
        assertThat(doc.get("end_session_endpoint").asString()).isEqualTo(ISSUER + "/connect/logout");
    }

    @Test
    void 로그인하지_않은_브라우저의_인가_요청은_로그인_화면으로() throws Exception {
        Pkce pkce = new Pkce();
        MvcResult result = mvc.perform(authorize(pkce)).andExpect(status().isFound()).andReturn();
        assertThat(location(result)).isEqualTo("/signin");
    }

    @Test
    void 인가_코드_흐름으로_받은_토큰의_sub_는_userId_roles_는_역할_iss_는_issuer() throws Exception {
        Tokens tokens = loginAndExchange();

        Jwt access = decode(tokens.accessToken());
        assertThat(access.getSubject()).isEqualTo(Long.toString(userId));
        assertThat(access.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(access.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(access.getExpiresAt()).isBefore(access.getIssuedAt().plusSeconds(15 * 60 + 1));

        Jwt id = decode(tokens.idToken());
        assertThat(id.getSubject()).isEqualTo(Long.toString(userId));
        assertThat(id.getAudience()).containsExactly(CLIENT);
        assertThat(tokens.refreshToken()).isNotBlank();
    }

    @Test
    void refresh_는_같은_refresh_token_을_다시_써도_되고_역할_변경을_반영한다() throws Exception {
        Tokens first = loginAndExchange();
        jdbc.sql("insert into user_roles (user_id, role) values (?, 'ADMIN')").param(userId).update();

        JsonNode refreshed = json(mvc.perform(refresh(first.refreshToken())).andExpect(status().isOk()).andReturn());
        // 회전하지 않는다: 같은 BFF 세션의 동시 요청이 옛 토큰으로 갱신해도 거절되지 않게 (5-2)
        assertThat(refreshed.get("refresh_token").asString()).isEqualTo(first.refreshToken());
        assertThat(decode(refreshed.get("access_token").asString()).getClaimAsStringList("roles"))
                .containsExactly("ADMIN", "USER");

        mvc.perform(refresh(first.refreshToken())).andExpect(status().isOk());
    }

    @Test
    void 정지된_사용자는_refresh_가_거절된다() throws Exception {
        Tokens tokens = loginAndExchange();
        jdbc.sql("update users set status = 'SUSPENDED' where id = ?").param(userId).update();

        mvc.perform(refresh(tokens.refreshToken())).andExpect(status().isBadRequest());
    }

    @Test
    void 틀린_비밀번호는_signin_error_로_다섯_번이면_잠겨_맞는_비밀번호도_locked() throws Exception {
        for (int i = 0; i < 5; i++) {
            Browser browser = new Browser();
            MvcResult failed = browser.submitLogin(loginId, "wrong-pass");
            assertThat(location(failed)).isEqualTo("/signin?error");
        }
        Browser browser = new Browser();
        assertThat(location(browser.submitLogin(loginId, "password1"))).isEqualTo("/signin?error=locked");
    }

    @Test
    void CSRF_토큰_없는_로그인_POST_는_403() throws Exception {
        mvc.perform(post("/login").param("username", loginId).param("password", "password1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrf_는_세션의_토큰을_본문으로_주고_쿠키로_내보내지_않는다() throws Exception {
        MvcResult result = mvc.perform(get("/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode body = json(result);
        assertThat(body.get("parameterName").asString()).isEqualTo("_csrf");
        assertThat(body.get("token").asString()).isNotBlank();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNull();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void SSO_로그아웃은_세션을_끝내고_인가_기록을_지워_refresh_도_막는다() throws Exception {
        Browser browser = new Browser();
        Tokens tokens = browser.loginAndExchange(loginId, "password1");
        assertThat(authorizations()).isPositive();

        // GET 은 쿼리 문자열로 (Gateway 의 RP-initiated logout 이 브라우저를 이 주소로 보낸다)
        MvcResult logout = browser.perform(get(UriComponentsBuilder.fromPath("/connect/logout")
                .queryParam("id_token_hint", tokens.idToken())
                .queryParam("post_logout_redirect_uri", "http://localhost:5173/")
                .queryParam("state", "s1")
                .encode().build().toUri()));
        assertThat(logout.getResponse().getStatus()).isEqualTo(302);
        assertThat(location(logout)).isEqualTo("http://localhost:5173/?state=s1");

        assertThat(authorizations()).isZero();
        mvc.perform(refresh(tokens.refreshToken())).andExpect(status().isBadRequest());
        // 같은 브라우저가 다시 인가 요청을 하면 로그인 화면이 나온다 (SSO 세션이 끝났다)
        MvcResult again = browser.perform(authorize(new Pkce()));
        assertThat(location(again)).isEqualTo("/signin");
    }

    @Test
    void 가입_폼은_회원을_만들고_바로_로그인해_저장된_인가_요청을_이어간다() throws Exception {
        Browser browser = new Browser();
        Pkce pkce = new Pkce();
        browser.perform(authorize(pkce));
        String csrf = browser.csrfToken();
        String newLoginId = "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MvcResult signedUp = browser.perform(post("/signup").param("loginId", newLoginId).param("password", "password1")
                .param("_csrf", csrf));
        assertThat(location(signedUp)).startsWith("/oauth2/authorize?");
        Long newUserId = jdbc.sql("select id from users where login_id = ?").param(newLoginId).query(Long.class).single();
        assertThat(jdbc.sql("select count(*) from outbox where aggregateid = ?").param(Long.toString(newUserId))
                .query(Long.class).single()).isEqualTo(1);

        MvcResult code = browser.perform(get(URI.create(location(signedUp))).accept(MediaType.TEXT_HTML));
        assertThat(location(code)).startsWith(REDIRECT + "?code=");
    }

    @Test
    void 가입_폼의_중복_아이디는_register_error_로() throws Exception {
        Browser browser = new Browser();
        String csrf = browser.csrfToken();
        MvcResult result = browser.perform(post("/signup").param("loginId", loginId).param("password", "password1")
                .param("_csrf", csrf));
        assertThat(location(result)).isEqualTo("register?error=duplicate");
    }

    @Test
    void 틀린_client_secret_으로는_토큰을_받을_수_없다() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic(CLIENT, "wrong"))
                        .param("grant_type", "refresh_token").param("refresh_token", "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 스크립트용_공개_클라이언트는_시크릿_없이_PKCE_로_사용자_토큰을_받고_refresh_는_없다() throws Exception {
        String redirect = "http://127.0.0.1/scripts/callback";
        Browser browser = new Browser();
        Pkce pkce = new Pkce();
        URI authorize = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code").queryParam("client_id", "scripts")
                .queryParam("redirect_uri", redirect).queryParam("scope", "openid").queryParam("state", "s")
                .queryParam("code_challenge", pkce.challenge()).queryParam("code_challenge_method", "S256")
                .encode().build().toUri();
        assertThat(location(browser.perform(get(authorize).accept(MediaType.TEXT_HTML)))).isEqualTo("/signin");
        String saved = location(browser.submitLogin(loginId, "password1"));
        String callback = location(browser.perform(get(URI.create(saved)).accept(MediaType.TEXT_HTML)));
        assertThat(callback).startsWith(redirect + "?code=");
        String code = UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("code");

        // PKCE 없이(verifier 틀림) 바꾸면 거절
        mvc.perform(post("/oauth2/token").param("client_id", "scripts").param("grant_type", "authorization_code")
                        .param("code", java.net.URLDecoder.decode(code, StandardCharsets.UTF_8))
                        .param("redirect_uri", redirect).param("code_verifier", "wrong-verifier-" + UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        // 위에서 코드를 한 번 썼으니 새로 받는다
        String saved2 = location(browser.perform(get(authorize).accept(MediaType.TEXT_HTML)));
        String code2 = UriComponentsBuilder.fromUriString(saved2).build().getQueryParams().getFirst("code");
        JsonNode token = json(mvc.perform(post("/oauth2/token").param("client_id", "scripts")
                        .param("grant_type", "authorization_code")
                        .param("code", java.net.URLDecoder.decode(code2, StandardCharsets.UTF_8))
                        .param("redirect_uri", redirect).param("code_verifier", pkce.verifier()))
                .andExpect(status().isOk()).andReturn());

        Jwt access = decode(token.get("access_token").asString());
        assertThat(access.getSubject()).isEqualTo(Long.toString(userId));
        assertThat(access.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(token.has("refresh_token")).isFalse();
    }

    @Test
    void 기기_목록은_이_브라우저를_지금_기기로_보이고_다른_기기를_로그아웃하면_그_기기의_refresh_만_막힌다() throws Exception {
        Browser laptop = new Browser();
        Tokens laptopTokens = laptop.loginAndExchange(loginId, "password1");
        Browser phone = new Browser();
        Tokens phoneTokens = phone.loginAndExchange(loginId, "password1");

        JsonNode devices = json(laptop.perform(get("/sessions").accept(MediaType.APPLICATION_JSON)));
        assertThat(devices.size()).isEqualTo(2);
        assertThat(devices.get(0).get("current").asBoolean()).isTrue();
        String phoneId = devices.get(1).get("id").asString();
        assertThat(devices.get(1).get("current").asBoolean()).isFalse();

        // CSRF 토큰(헤더) 없이 지우면 403
        assertThat(laptop.perform(delete("/sessions/" + phoneId)).getResponse().getStatus()).isEqualTo(403);
        MvcResult deleted = laptop.perform(delete("/sessions/" + phoneId).header("X-CSRF-TOKEN", laptop.csrfToken()));
        assertThat(deleted.getResponse().getStatus()).isEqualTo(204);

        mvc.perform(refresh(phoneTokens.refreshToken())).andExpect(status().isBadRequest());
        mvc.perform(refresh(laptopTokens.refreshToken())).andExpect(status().isOk());
        // 로그아웃된 기기의 SSO 세션도 끝났다: 인가 요청이 로그인 화면으로
        assertThat(location(phone.perform(authorize(new Pkce())))).isEqualTo("/signin");
        assertThat(json(laptop.perform(get("/sessions"))).size()).isEqualTo(1);
    }

    @Test
    void 지금_기기는_기기_API_로_지우지_않고_없는_기기는_404() throws Exception {
        Browser mine = new Browser();
        mine.loginAndExchange(loginId, "password1");
        String current = json(mine.perform(get("/sessions"))).get(0).get("id").asString();

        String csrf = mine.csrfToken();
        assertThat(mine.perform(delete("/sessions/" + current).header("X-CSRF-TOKEN", csrf))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(mine.perform(delete("/sessions/" + UUID.randomUUID()).header("X-CSRF-TOKEN", csrf))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void 다른_기기_모두_로그아웃은_지금_기기만_남긴다() throws Exception {
        Browser mine = new Browser();
        Tokens mineTokens = mine.loginAndExchange(loginId, "password1");
        Tokens other1 = new Browser().loginAndExchange(loginId, "password1");
        Tokens other2 = new Browser().loginAndExchange(loginId, "password1");

        assertThat(mine.perform(delete("/sessions").header("X-CSRF-TOKEN", mine.csrfToken()))
                .getResponse().getStatus()).isEqualTo(204);

        mvc.perform(refresh(other1.refreshToken())).andExpect(status().isBadRequest());
        mvc.perform(refresh(other2.refreshToken())).andExpect(status().isBadRequest());
        mvc.perform(refresh(mineTokens.refreshToken())).andExpect(status().isOk());
    }

    @Test
    void 로그인하지_않은_기기_API_는_401_이고_기기_화면_주소는_로그인을_거쳐_돌아온다() throws Exception {
        mvc.perform(get("/sessions").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());

        Browser browser = new Browser();
        assertThat(location(browser.perform(get("/sessions/login").accept(MediaType.TEXT_HTML)))).isEqualTo("/signin");
        String back = location(browser.submitLogin(loginId, "password1"));
        assertThat(back).startsWith("http://localhost/sessions/login");
        assertThat(location(browser.perform(get(URI.create(back)).accept(MediaType.TEXT_HTML)))).isEqualTo("/devices");
    }

    @Test
    void SSO_로그아웃은_이_기기의_인가_기록만_지운다() throws Exception {
        Browser laptop = new Browser();
        Tokens laptopTokens = laptop.loginAndExchange(loginId, "password1");
        Tokens phoneTokens = new Browser().loginAndExchange(loginId, "password1");

        laptop.perform(get(UriComponentsBuilder.fromPath("/connect/logout")
                .queryParam("id_token_hint", laptopTokens.idToken())
                .queryParam("post_logout_redirect_uri", "http://localhost:5173/")
                .encode().build().toUri()));

        mvc.perform(refresh(laptopTokens.refreshToken())).andExpect(status().isBadRequest());
        mvc.perform(refresh(phoneTokens.refreshToken())).andExpect(status().isOk());
    }

    // ---- helpers ----

    private Tokens loginAndExchange() throws Exception {
        return new Browser().loginAndExchange(loginId, "password1");
    }

    private long authorizations() {
        return jdbc.sql("select count(*) from oauth2_authorization where principal_name = ?")
                .param(Long.toString(userId)).query(Long.class).single();
    }

    // SAS 는 GET 인가 요청의 값을 쿼리 문자열에서 읽는다
    static MockHttpServletRequestBuilder authorize(Pkce pkce) {
        URI uri = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT)
                .queryParam("redirect_uri", REDIRECT)
                .queryParam("scope", "openid")
                .queryParam("state", "st-" + UUID.randomUUID())
                .queryParam("code_challenge", pkce.challenge())
                .queryParam("code_challenge_method", "S256")
                .encode().build().toUri();
        return get(uri).accept(MediaType.TEXT_HTML);
    }

    static MockHttpServletRequestBuilder refresh(String refreshToken) {
        return post("/oauth2/token").with(httpBasic(CLIENT, SECRET))
                .param("grant_type", "refresh_token").param("refresh_token", refreshToken);
    }

    private Jwt decode(String token) throws Exception {
        JWKSet keys = JWKSet.parse(mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        RSAKey key = (RSAKey) keys.getKeyByKeyId(SignedJWT.parse(token).getHeader().getKeyID());
        assertThat(key).isNotNull();
        assertThat(key.isPrivate()).isFalse();
        return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(token);
    }

    static String location(MvcResult result) {
        return result.getResponse().getHeader(HttpHeaders.LOCATION);
    }

    static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    record Tokens(String accessToken, String refreshToken, String idToken) {
    }

    record Pkce(String verifier) {
        Pkce() {
            this(UUID.randomUUID() + "-" + UUID.randomUUID());
        }

        String challenge() {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** SSO 세션 쿠키({@code AUTH_SESSION})를 들고 다니는 브라우저 */
    class Browser {

        private Cookie session;

        MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
            if (session != null) {
                request.cookie(session);
            }
            MvcResult result = mvc.perform(request).andReturn();
            // Spring Session 은 쿠키를 Set-Cookie 헤더로 직접 쓴다 (response.addCookie 가 아님)
            for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
                if (header.startsWith(SESSION + "=")) {
                    String value = header.substring(SESSION.length() + 1, header.indexOf(';'));
                    session = value.isEmpty() || header.contains("Max-Age=0") ? null : new Cookie(SESSION, value);
                }
            }
            return result;
        }

        String csrfToken() throws Exception {
            // 인증 화면 SPA 처럼 GET /csrf 의 본문에서 (세션에 둔 토큰)
            return json(perform(get("/csrf"))).get("token").asString();
        }

        MvcResult submitLogin(String id, String password) throws Exception {
            String csrf = csrfToken();
            return perform(post("/login").param("username", id).param("password", password).param("_csrf", csrf));
        }

        Tokens loginAndExchange(String id, String password) throws Exception {
            Pkce pkce = new Pkce();
            assertThat(location(perform(authorize(pkce)))).isEqualTo("/signin");
            MvcResult loggedIn = submitLogin(id, password);
            String saved = location(loggedIn);
            assertThat(saved).startsWith("http://localhost/oauth2/authorize");

            MvcResult authorized = perform(get(URI.create(saved)).accept(MediaType.TEXT_HTML));
            String callback = location(authorized);
            assertThat(callback).startsWith(REDIRECT + "?code=");
            String code = UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("code");

            JsonNode token = json(mvc.perform(post("/oauth2/token").with(httpBasic(CLIENT, SECRET))
                            .param("grant_type", "authorization_code")
                            .param("code", java.net.URLDecoder.decode(code, StandardCharsets.UTF_8))
                            .param("redirect_uri", REDIRECT)
                            .param("code_verifier", pkce.verifier()))
                    .andExpect(status().isOk()).andReturn());
            return new Tokens(token.get("access_token").asString(), token.get("refresh_token").asString(),
                    token.get("id_token").asString());
        }
    }
}
