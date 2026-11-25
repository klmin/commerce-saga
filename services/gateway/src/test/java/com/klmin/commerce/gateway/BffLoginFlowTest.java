package com.klmin.commerce.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 로그인 시작·돌아갈 곳·전달 헤더 규칙 (설계 문서 7-3·7-4) */
class BffLoginFlowTest {

    private static BffProperties hostMode() {
        return new BffProperties("https://auth.example.com", "http://auth-service:8086", "admin.example.com",
                "https://example.com/", "https://admin.example.com/",
                new BffProperties.Client("s", "https://example.com/login/oauth2/code/gateway-web"),
                new BffProperties.Client("s", "https://admin.example.com/login/oauth2/code/gateway-admin"), "");
    }

    private static BffProperties pathMode() {
        return new BffProperties("https://example.com/auth", "http://auth-service:8086", "", "/", "/admin/",
                new BffProperties.Client("s", "https://example.com/login/oauth2/code/gateway-web"),
                new BffProperties.Client("s", "https://example.com/login/oauth2/code/gateway-admin"), "");
    }

    @Test
    void return_to_는_같은_출처의_경로만() {
        assertThat(BffLoginFlow.safePath("/orders/1?tab=saga")).contains("/orders/1?tab=saga");
        assertThat(BffLoginFlow.safePath("/admin/")).contains("/admin/");
        assertThat(BffLoginFlow.safePath("//evil.example")).isEmpty();
        assertThat(BffLoginFlow.safePath("/\\evil.example")).isEmpty();
        assertThat(BffLoginFlow.safePath("https://evil.example")).isEmpty();
        assertThat(BffLoginFlow.safePath("/\t/evil.example")).isEmpty();
        assertThat(BffLoginFlow.safePath("/a b")).isEmpty();
        assertThat(BffLoginFlow.safePath("")).isEmpty();
        assertThat(BffLoginFlow.safePath(null)).isEmpty();
    }

    @Test
    void 호스트_방식은_관리자_호스트면_gateway_admin() {
        BffLoginFlow flow = new BffLoginFlow(hostMode(), null);
        assertThat(flow.chooseClient(exchange("https://admin.example.com/oauth2/authorization/auto"))).isEqualTo("gateway-admin");
        assertThat(flow.chooseClient(exchange("https://example.com/oauth2/authorization/auto"))).isEqualTo("gateway-web");
        assertThat(flow.chooseClient(exchange("https://ADMIN.example.com/oauth2/authorization/auto"))).isEqualTo("gateway-admin");
    }

    @Test
    void 경로_방식은_관리자_호스트를_비워_늘_gateway_web() {
        BffLoginFlow flow = new BffLoginFlow(pathMode(), null);
        assertThat(flow.chooseClient(exchange("https://example.com/oauth2/authorization/auto"))).isEqualTo("gateway-web");
    }

    @Test
    void 로그아웃_뒤_홈은_허용된_둘_중_하나만() {
        BffLoginFlow host = new BffLoginFlow(hostMode(), null);
        assertThat(host.logoutHome("gateway-admin", null)).isEqualTo("https://admin.example.com/");
        assertThat(host.logoutHome("gateway-web", "/admin/")).isEqualTo("https://example.com/");

        BffLoginFlow path = new BffLoginFlow(pathMode(), null);
        assertThat(path.logoutHome("gateway-web", "/admin/products")).isEqualTo("/admin/");
        assertThat(path.logoutHome("gateway-web", "/orders")).isEqualTo("/");
        assertThat(path.logoutHome("gateway-web", null)).isEqualTo("/");
    }

    @Test
    void 경로로_준_홈은_redirect_URI_의_출처를_붙여_절대_주소로() {
        ClientRegistration web = ClientRegistration.withRegistrationId("gateway-web").clientId("gateway-web")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.com:8443/login/oauth2/code/gateway-web")
                .authorizationUri("https://example.com/auth/oauth2/authorize").tokenUri("http://auth/oauth2/token").build();
        assertThat(BffLoginFlow.absolute("/admin/", web)).isEqualTo("https://example.com:8443/admin/");
        assertThat(BffLoginFlow.absolute("https://admin.example.com/", web)).isEqualTo("https://admin.example.com/");
    }

    @Test
    void 믿는_프록시의_전달_헤더만_요청_주소에_반영한다() {
        TrustedForwardedHeadersFilter filter =
                new TrustedForwardedHeadersFilter(new ClientIpResolver(List.of("10.0.0.0/8")));

        assertThat(filtered(filter, "10.1.2.3")).isEqualTo("https://admin.example.com/login/oauth2/code/gateway-admin");
        // 믿지 않는 연결(브라우저가 직접)이 보낸 헤더는 무시
        assertThat(filtered(filter, "203.0.113.9")).isEqualTo("http://gateway:8000/login/oauth2/code/gateway-admin");
    }

    private static String filtered(TrustedForwardedHeadersFilter filter, String peer) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("http://gateway:8000/login/oauth2/code/gateway-admin")
                .remoteAddress(new InetSocketAddress(peer, 40000))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "admin.example.com"));
        AtomicReference<String> seen = new AtomicReference<>();
        WebFilterChain chain = e -> {
            seen.set(e.getRequest().getURI().toString());
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return seen.get();
    }

    private static ServerWebExchange exchange(String url) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(url));
    }
}
