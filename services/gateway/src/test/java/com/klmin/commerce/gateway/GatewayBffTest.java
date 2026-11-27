package com.klmin.commerce.gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway 를 BFF 로 띄워 로그인부터 API 릴레이·갱신·로그아웃까지 확인한다 (후속 5-2, ADR 0017).
 * 뒤의 서비스와 SAS(인가 서버)는 JDK HttpServer 하나가 흉내 낸다: {@code /oauth2/token} 은 테스트 키로 서명한 토큰을 주고,
 * 나머지 경로는 받은 경로·Authorization 을 기록한다. 브라우저는 쿠키를 들고 다니는 HttpClient(리다이렉트는 따라가지 않음).
 * 화면 주소는 로컬 개발 기본값({@code localhost:5173}, 관리자 {@code localhost:5174})이라 요청에 그 Host 를 붙인다
 * (Vite 프록시가 원래 Host 를 넘기는 것과 같다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class GatewayBffTest {

    static {
        // 테스트가 Host 헤더를 화면 주소로 바꿔 보낼 수 있게
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.4-alpine"))
            .withExposedPorts(6379);

    private static final RSAKey KEY = rsaKey();
    private static final AtomicReference<String> ISSUER_HOLDER = new AtomicReference<>();
    private static final String USER_ID = "01900000-0000-7000-8001-000000000008";
    private static final AtomicReference<String> NONCE = new AtomicReference<>();
    private static final AtomicReference<String> AUDIENCE = new AtomicReference<>("gateway-web");
    private static final AtomicReference<Long> ACCESS_TTL = new AtomicReference<>(600L);
    private static final AtomicReference<List<String>> ROLES = new AtomicReference<>(List.of("USER"));
    private static final java.util.concurrent.atomic.AtomicBoolean REFRESH_REFUSED = new java.util.concurrent.atomic.AtomicBoolean();
    private static final List<String> TOKEN_REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<String> LAST_API = new AtomicReference<>();
    private static final HttpServer SAS = fakeAuthorizationServerAndServices();
    private static final String ISSUER = "http://localhost:" + SAS.getAddress().getPort();

    @Value("${local.server.port}")
    int port;

    private HttpClient browser;
    private CookieManager cookies;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        for (String service : List.of("AUTH", "ORDER", "INVENTORY", "POINT", "PAYMENT", "NOTIFICATION", "PRODUCT", "SEARCH")) {
            registry.add(service + "_SERVICE_URL", () -> ISSUER);
        }
        registry.add("AUTH_ISSUER", () -> ISSUER);
        registry.add("AUTH_INTERNAL_URL", () -> ISSUER);
        registry.add("gateway.bff.secret-dev-dir", () -> "");
        registry.add("gateway.bff.web.secret", () -> "test-web-secret");
        registry.add("gateway.bff.admin.secret", () -> "test-admin-secret");
        registry.add("gateway.bff.cookie-secure", () -> "false");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        SAS.stop(0);
    }

    @BeforeEach
    void newBrowser() {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        browser = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        ACCESS_TTL.set(600L);
        AUDIENCE.set("gateway-web");
        ROLES.set(List.of("USER"));
        REFRESH_REFUSED.set(false);
        TOKEN_REQUESTS.clear();
        LAST_API.set(null);
    }

    @Test
    void 세션이_없으면_API_는_리다이렉트가_아니라_401() throws Exception {
        HttpResponse<String> response = get("/api/orders/abc", "localhost:5173");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(LAST_API.get()).isNull();
    }

    @Test
    void 로그인_시작은_호스트로_클라이언트를_고르고_PKCE_를_붙인다() throws Exception {
        Map<String, String> web = query(location(get("/oauth2/authorization/auto", "localhost:5173")));
        assertThat(web).containsEntry("client_id", "gateway-web")
                .containsEntry("redirect_uri", "http://localhost:5173/login/oauth2/code/gateway-web")
                .containsEntry("code_challenge_method", "S256")
                .containsKeys("state", "nonce", "code_challenge");

        Map<String, String> admin = query(location(get("/oauth2/authorization/auto", "localhost:5174")));
        assertThat(admin).containsEntry("client_id", "gateway-admin")
                .containsEntry("redirect_uri", "http://localhost:5174/login/oauth2/code/gateway-admin");
    }

    @Test
    void 로그인하면_return_to_로_돌아오고_세션으로_부른_API_에_Bearer_가_붙는다() throws Exception {
        String home = login("/orders/1?tab=saga", "localhost:5173");
        assertThat(home).isEqualTo("/orders/1?tab=saga");
        assertThat(cookie("GW_SESSION")).isNotNull();
        assertThat(cookie("GW_SESSION").isHttpOnly()).isTrue();
        assertThat(TOKEN_REQUESTS).anyMatch(r -> r.contains("grant_type=authorization_code") && r.contains("code_verifier="));

        HttpResponse<String> api = get("/api/orders/abc", "localhost:5173");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(LAST_API.get()).contains("path=/orders/abc", "auth=Bearer ey", "cookie=null");
    }

    @Test
    void api_me_는_세션의_사용자와_역할을_Gateway_가_답하고_없으면_401() throws Exception {
        assertThat(get("/api/me", "localhost:5173").statusCode()).isEqualTo(401);

        ROLES.set(List.of("USER", "ADMIN"));
        login("/", "localhost:5173");
        HttpResponse<String> me = get("/api/me", "localhost:5173");
        assertThat(me.statusCode()).isEqualTo(200);
        // 로그인 기기 화면 주소 = SAS 공개 주소 + /sessions/login (후속 5-6)
        assertThat(me.body()).isEqualTo("{\"userId\":\"" + USER_ID + "\",\"roles\":[\"ADMIN\",\"USER\"],\"devicesUrl\":\""
                + ISSUER + "/sessions/login\"}");
        // 서비스로 넘기지 않는다
        assertThat(LAST_API.get()).isNull();
    }

    @Test
    void 같은_세션으로_API_를_동시에_불러도_세션이_유지된다() throws Exception {
        login("/", "localhost:5173");
        get("/api/orders/abc", "localhost:5173");
        String xsrf = cookie("XSRF-TOKEN").getValue();
        // 화면은 한 번에 API 를 여러 개 부른다 (홈: 내 정보·포인트·상품·재고 여러 개, 주문 버튼 연타)
        List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            HttpRequest.Builder request = request("/api/orders/c" + i, "localhost:5173");
            calls.add(browser.sendAsync(i % 2 == 0 ? request.GET().build()
                            : request.header("X-XSRF-TOKEN", xsrf).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        for (var call : calls) {
            HttpResponse<String> response = call.get();
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
        assertThat(get("/api/me", "localhost:5173").statusCode()).isEqualTo(200);
    }

    @Test
    void 기다리다_끊은_요청이_있어도_다음_요청은_바로_답한다() throws Exception {
        login("/", "localhost:5173");
        // 화면 이동으로 브라우저가 진행 중인 API 를 끊는다(재고 여러 개)
        List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> aborted = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            aborted.add(browser.sendAsync(request("/api/orders/slow-" + i, "localhost:5173")
                    .timeout(java.time.Duration.ofMillis(300)).GET().build(), HttpResponse.BodyHandlers.ofString()));
        }
        for (var call : aborted) {
            assertThat(call).failsWithin(java.time.Duration.ofSeconds(5));
        }
        long started = System.nanoTime();
        HttpResponse<String> me = browser.send(request("/api/me", "localhost:5173")
                .timeout(java.time.Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> api = browser.send(request("/api/orders/next", "localhost:5173")
                .timeout(java.time.Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(3));
    }

    @Test
    void 바깥_주소_return_to_는_버리고_클라이언트의_홈으로() throws Exception {
        assertThat(login("//evil.example/x", "localhost:5173")).isEqualTo("http://localhost:5173/");
        newBrowser();
        assertThat(login("/\\evil.example", "localhost:5173")).isEqualTo("http://localhost:5173/");
        newBrowser();
        AUDIENCE.set("gateway-admin");
        assertThat(login(null, "localhost:5174")).isEqualTo("http://localhost:5174/");
    }

    @Test
    void 쿠키로_보내는_상태_변경은_XSRF_토큰이_있어야_한다() throws Exception {
        login("/", "localhost:5173");
        get("/api/orders/abc", "localhost:5173");
        String xsrf = cookie("XSRF-TOKEN").getValue();
        assertThat(cookie("XSRF-TOKEN").isHttpOnly()).isFalse();

        assertThat(post("/api/orders", "localhost:5173", null).statusCode()).isEqualTo(403);
        HttpResponse<String> ok = post("/api/orders", "localhost:5173", xsrf);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(LAST_API.get()).contains("path=/orders", "auth=Bearer ey", "cookie=null");
    }

    @Test
    void access_token_이_곧_끝나면_refresh_로_갱신해_붙인다() throws Exception {
        ACCESS_TTL.set(30L);  // 기본 허용 오차(60초) 안이라 다음 요청 때 갱신된다
        login("/", "localhost:5173");
        ACCESS_TTL.set(600L);

        get("/api/orders/abc", "localhost:5173");
        assertThat(TOKEN_REQUESTS).anyMatch(r -> r.contains("grant_type=refresh_token") && r.contains("refresh_token=r-1"));
        assertThat(LAST_API.get()).contains("auth=Bearer ey");
    }

    @Test
    void 관리자는_토큰이_갱신된_뒤에도_관리자_API_를_쓸_수_있다() throws Exception {
        ROLES.set(List.of("ADMIN", "USER"));
        AUDIENCE.set("gateway-admin");
        ACCESS_TTL.set(30L);
        login("/", "localhost:5174");
        ACCESS_TTL.set(600L);

        assertThat(get("/api/admin/payment-faults", "localhost:5174").statusCode()).isEqualTo(200);
        assertThat(TOKEN_REQUESTS).anyMatch(r -> r.contains("grant_type=refresh_token"));
        // 갱신으로 세션의 로그인 사용자가 다시 만들어진 뒤에도 ROLE_ADMIN 이 남아 있다
        assertThat(get("/api/admin/payment-faults", "localhost:5174").statusCode()).isEqualTo(200);
    }

    @Test
    void refresh_가_거절되면_세션을_끝내고_API_는_401() throws Exception {
        ACCESS_TTL.set(30L);
        login("/", "localhost:5173");
        REFRESH_REFUSED.set(true);

        HttpResponse<String> refused = get("/api/orders/abc", "localhost:5173");
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(LAST_API.get()).isNull();
        // 세션이 끝나 다음 요청도 401 (다시 로그인할 때까지)
        REFRESH_REFUSED.set(false);
        assertThat(get("/api/orders/abc", "localhost:5173").statusCode()).isEqualTo(401);
    }

    @Test
    void 로그아웃은_세션을_지우고_SAS_OIDC_로그아웃으로_보낸다() throws Exception {
        login("/", "localhost:5173");
        get("/api/orders/abc", "localhost:5173");
        String xsrf = cookie("XSRF-TOKEN").getValue();

        HttpResponse<String> logout = browser.send(request("/logout", "localhost:5173")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + xsrf + "&return_to=%2Forders"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(302);
        String target = location(logout);
        assertThat(target).startsWith(ISSUER + "/connect/logout?");
        Map<String, String> params = query(target);
        assertThat(params).containsKey("id_token_hint")
                .containsEntry("post_logout_redirect_uri", "http://localhost:5173/");

        assertThat(get("/api/orders/abc", "localhost:5173").statusCode()).isEqualTo(401);
    }

    @Test
    void Bearer_로_온_요청은_세션_없이도_그대로_검증해_넘긴다() throws Exception {
        String token = jwt(Map.of("sub", "01900000-0000-7000-8001-000000000042", "roles", List.of("USER")), 300, "gateway-web", null);
        HttpResponse<String> response = browser.send(request("/api/orders/abc", "localhost:5173")
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(LAST_API.get()).contains("auth=Bearer " + token);
    }

    // ---- 브라우저 흉내 ----

    /** 로그인 시작 → (SAS 는 건너뛰고) 인가 코드 콜백 → 돌아갈 곳 */
    private String login(String returnTo, String host) throws Exception {
        String start = returnTo == null ? "/oauth2/authorization/auto"
                : UriComponentsBuilder.fromPath("/oauth2/authorization/auto").queryParam("return_to", returnTo)
                .encode().build().toUriString();
        Map<String, String> authorize = query(location(get(start, host)));
        NONCE.set(authorize.get("nonce"));
        String callback = URI.create(authorize.get("redirect_uri")).getRawPath()
                + "?code=c-" + UUID.randomUUID() + "&state=" + URLEncoderHolder.encode(authorize.get("state"));
        HttpResponse<String> done = get(callback, host);
        assertThat(done.statusCode()).as("callback " + done.body()).isEqualTo(302);
        return location(done);
    }

    private HttpResponse<String> get(String path, String host) throws Exception {
        return browser.send(request(path, host).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String host, String xsrf) throws Exception {
        HttpRequest.Builder builder = request(path, host).header("Content-Type", "application/json");
        if (xsrf != null) {
            builder.header("X-XSRF-TOKEN", xsrf);
        }
        return browser.send(builder.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String host) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Host", host);
    }

    private HttpCookie cookie(String name) {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name)).findFirst()
                .orElse(null);
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location").orElseThrow(
                () -> new AssertionError("Location 없음: " + response.statusCode()));
    }

    private static Map<String, String> query(String url) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> URLDecoder.decode(e.getValue(), StandardCharsets.UTF_8)));
    }

    private static final class URLEncoderHolder {
        static String encode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    // ---- 가짜 SAS + 서비스 ----

    private static String jwt(Map<String, Object> claims, long ttlSeconds, String audience, String nonce) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issuer(ISSUER_HOLDER.get())
                    .audience(audience).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(ttlSeconds)));
            claims.forEach(builder::claim);
            if (nonce != null) {
                builder.claim("nonce", nonce);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID())
                    .type(JOSEObjectType.JWT).build(), builder.build());
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer fakeAuthorizationServerAndServices() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            ISSUER_HOLDER.set("http://localhost:" + server.getAddress().getPort());
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/oauth2/jwks")) {
                    respond(exchange, new JWKSet(KEY.toPublicJWK()).toString());
                } else if (path.equals("/oauth2/token")) {
                    String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    TOKEN_REQUESTS.add(form + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
                    boolean refresh = form.contains("grant_type=refresh_token");
                    if (refresh && REFRESH_REFUSED.get()) {
                        // SAS 가 refresh 를 거절(만료·SSO 로그아웃으로 기록 삭제)
                        respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
                        return;
                    }
                    String access = jwt(Map.of("sub", USER_ID, "roles", ROLES.get()), ACCESS_TTL.get(), AUDIENCE.get(), null);
                    String id = jwt(Map.of("sub", USER_ID, "roles", ROLES.get(), "auth_time", Instant.now().getEpochSecond()),
                            600, AUDIENCE.get(), refresh ? null : NONCE.get());
                    respond(exchange, """
                            {"access_token":"%s","token_type":"Bearer","expires_in":%d,"scope":"openid",
                             "refresh_token":"%s","id_token":"%s"}
                            """.formatted(access, ACCESS_TTL.get(), refresh ? "r-2" : "r-1", id));
                } else {
                    if (path.startsWith("/orders/slow")) {
                        // 느린 서비스: 브라우저가 기다리다 끊는다(화면 이동)
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    LAST_API.set("path=" + path
                            + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                            + " cookie=" + exchange.getRequestHeaders().getFirst("Cookie"));
                    respond(exchange, "{}");
                }
            });
            // 느린 요청이 다른 요청을 막지 않게
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("bff-test-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
