package com.klmin.commerce.authservice;

import com.klmin.commerce.authservice.auth.application.port.in.SignUpCommand;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpUseCase;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 경로 방식(같은 도메인, {@code example.com/auth/...}) 확인 (설계 문서 7-4, 후속 5-1).
 * auth-service 는 루트에서 돌고, 앞단 프록시가 {@code /auth} 를 떼면서 {@code X-Forwarded-Prefix: /auth} 를 붙인다.
 * 그때 issuer 에 경로가 붙고(…/auth), 로그인 화면 리다이렉트·세션 쿠키 경로에도 {@code /auth} 가 붙어야 한다.
 */
@SpringBootTest(properties = {"auth.cookie.secure=false", "outbox.relay.fixed-delay=1h", "auth.jwt.dev-key-file=",
        "auth.oauth.secret-dev-dir=", "auth.oauth.clients.gateway-web.secret=test-web-secret",
        "auth.oauth.clients.gateway-admin.secret=test-admin-secret",
        "auth.issuer=" + OAuthPathModeIntegrationTest.ISSUER,
        "auth.oauth.clients.gateway-web.redirect-uris=" + OAuthPathModeIntegrationTest.REDIRECT,
        "auth.oauth.clients.gateway-web.post-logout-redirect-uris=http://example.test/,http://example.test/admin/"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class OAuthPathModeIntegrationTest {

    static final String ISSUER = "http://example.test/auth";
    static final String REDIRECT = "http://example.test/login/oauth2/code/gateway-web";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired
    MockMvc mvc;

    @Autowired
    SignUpUseCase signUp;

    @Test
    void 경로가_붙은_issuer_로_discovery_와_토큰_iss_가_나온다() throws Exception {
        JsonNode doc = JSON.readTree(proxied(get("/.well-known/openid-configuration")).getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(doc.get("issuer").asString()).isEqualTo(ISSUER);
        assertThat(doc.get("authorization_endpoint").asString()).isEqualTo(ISSUER + "/oauth2/authorize");
        assertThat(doc.get("end_session_endpoint").asString()).isEqualTo(ISSUER + "/connect/logout");
    }

    @Test
    void 로그인_화면_리다이렉트와_세션_쿠키_경로에_auth_가_붙고_토큰까지_받는다() throws Exception {
        String loginId = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID userId = signUp.signUp(new SignUpCommand(loginId, "password1"));
        OAuthFlowIntegrationTest.Pkce pkce = new OAuthFlowIntegrationTest.Pkce();

        // 인가 요청을 세션에 저장하며 SSO 세션 쿠키가 생긴다. 경로는 /auth (같은 도메인의 Gateway 세션 GW_SESSION 은 /)
        MvcResult start = proxied(authorize(pkce));
        assertThat(location(start)).isEqualTo("/auth/signin");
        assertThat(sessionHeader(start)).contains("Path=/auth;").contains("HttpOnly").contains("SameSite=Lax");
        Cookie session = session(start);

        // 로그인 화면(인증 화면 SPA)이 하듯 CSRF 토큰을 GET /csrf 본문으로 (/signin 은 SPA 라 auth-service 가 그리지 않는다)
        String csrf = JSON.readTree(proxied(get("/csrf").cookie(session))
                .getResponse().getContentAsString(StandardCharsets.UTF_8)).get("token").asString();

        MvcResult loggedIn = proxied(post("/login").cookie(session)
                .param("username", loginId).param("password", "password1").param("_csrf", csrf));
        Cookie afterLogin = session(loggedIn) != null ? session(loggedIn) : session;
        assertThat(location(loggedIn)).startsWith("http://example.test/auth/oauth2/authorize");

        MvcResult authorized = proxied(get(URI.create(location(loggedIn).replace("http://example.test/auth", "")))
                .accept(MediaType.TEXT_HTML).cookie(afterLogin));
        assertThat(location(authorized)).startsWith(REDIRECT + "?code=");
        String code = UriComponentsBuilder.fromUriString(location(authorized)).build().getQueryParams().getFirst("code");

        JsonNode token = JSON.readTree(proxied(post("/oauth2/token").with(httpBasic("gateway-web", "test-web-secret"))
                        .param("grant_type", "authorization_code")
                        .param("code", java.net.URLDecoder.decode(code, StandardCharsets.UTF_8))
                        .param("redirect_uri", REDIRECT)
                        .param("code_verifier", pkce.verifier()))
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        SignedJWT access = SignedJWT.parse(token.get("access_token").asString());
        assertThat(access.getJWTClaimsSet().getIssuer()).isEqualTo(ISSUER);
        assertThat(access.getJWTClaimsSet().getSubject()).isEqualTo(userId.toString());
    }

    // 앞단 프록시가 한 것처럼: 공개 호스트와 /auth 접두어를 헤더로
    private MvcResult proxied(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                .header("X-Forwarded-Host", "example.test")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Prefix", "/auth")).andReturn();
    }

    private static MockHttpServletRequestBuilder authorize(OAuthFlowIntegrationTest.Pkce pkce) {
        URI uri = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "gateway-web")
                .queryParam("redirect_uri", REDIRECT)
                .queryParam("scope", "openid")
                .queryParam("state", "st-" + UUID.randomUUID())
                .queryParam("code_challenge", pkce.challenge())
                .queryParam("code_challenge_method", "S256")
                .encode().build().toUri();
        return get(uri).accept(MediaType.TEXT_HTML);
    }

    private static String location(MvcResult result) {
        return result.getResponse().getHeader(HttpHeaders.LOCATION);
    }

    private static String sessionHeader(MvcResult result) {
        List<String> headers = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        return headers.stream().filter(h -> h.startsWith("AUTH_SESSION=")).findFirst().orElse("");
    }

    private static Cookie session(MvcResult result) {
        String header = sessionHeader(result);
        if (header.isEmpty()) {
            return null;
        }
        return new Cookie("AUTH_SESSION", header.substring("AUTH_SESSION=".length(), header.indexOf(';')));
    }
}
