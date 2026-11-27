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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway 를 실제로 띄워 라우팅·JWT 1차 검증·역할·rate limit 을 확인한다.
 * 뒤의 서비스와 auth-service JWKS 는 JDK HttpServer 하나가 흉내 낸다(받은 경로와 헤더를 되돌려줌). Redis 는 Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "GATEWAY_USER_RATE=1", "GATEWAY_USER_BURST=3", "GATEWAY_AUTH_RATE=1", "GATEWAY_AUTH_BURST=3"})
@Testcontainers(disabledWithoutDocker = true)
class GatewayRoutingTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.4-alpine"))
            .withExposedPorts(6379);

    private static final String ISSUER = "http://auth.test";
    private static final RSAKey KEY = rsaKey("test-key");
    private static final RSAKey OTHER_KEY = rsaKey("other-key");
    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();
    private static final HttpServer DOWNSTREAM = downstream();

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + DOWNSTREAM.getAddress().getPort();
        for (String service : List.of("AUTH", "ORDER", "INVENTORY", "POINT", "PAYMENT", "NOTIFICATION", "PRODUCT", "SEARCH")) {
            registry.add(service + "_SERVICE_URL", () -> base);
        }
        registry.add("AUTH_INTERNAL_URL", () -> base);
        registry.add("AUTH_ISSUER", () -> ISSUER);
        // BFF 클라이언트 시크릿은 테스트용 값 (로컬 개발용 파일을 만들지 않게)
        registry.add("gateway.bff.secret-dev-dir", () -> "");
        registry.add("gateway.bff.web.secret", () -> "test-web-secret");
        registry.add("gateway.bff.admin.secret", () -> "test-admin-secret");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.stop(0);
    }

    @Test
    void 토큰이_없거나_위조됐거나_만료되면_401_이고_서비스까지_가지_않는다() throws Exception {
        LAST_REQUEST.set(null);

        assertThat(get("/api/orders/abc", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/orders/abc", token(OTHER_KEY, "01900000-0000-7000-8001-000000000007", List.of("USER"), 300)).statusCode()).isEqualTo(401);
        assertThat(get("/api/orders/abc", token(KEY, "01900000-0000-7000-8001-000000000007", List.of("USER"), -60)).statusCode()).isEqualTo(401);
        assertThat(LAST_REQUEST.get()).isNull();
    }

    @Test
    void 유효한_토큰이면_api_접두사를_떼고_Authorization_은_그대로_X_User_Id_는_지워서_넘긴다() throws Exception {
        String token = token(KEY, "01900000-0000-7000-8001-000000000008", List.of("USER"), 300);

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/orders/abc"))
                .header("Authorization", "Bearer " + token).header("X-User-Id", "1").GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(LAST_REQUEST.get()).contains("path=/orders/abc", "auth=Bearer " + token, "x-user-id=null");
    }

    @Test
    void 목록_API_는_뒤에_경로가_없어도_서비스로_넘긴다() throws Exception {
        String token = token(KEY, "01900000-0000-7000-8001-000000000008", List.of("USER"), 300);

        assertThat(get("/api/orders?size=5", token).statusCode()).isEqualTo(200);
        assertThat(LAST_REQUEST.get()).contains("path=/orders");
        assertThat(get("/api/payments?size=5", token).statusCode()).isEqualTo(200);
        assertThat(LAST_REQUEST.get()).contains("path=/payments");
        assertThat(get("/api/cart", token).statusCode()).isEqualTo(200);
        assertThat(LAST_REQUEST.get()).contains("path=/cart");
    }

    @Test
    void 관리자_경로는_ADMIN_역할만() throws Exception {
        assertThat(get("/api/admin/payment-faults", token(KEY, "01900000-0000-7000-8001-000000000009", List.of("USER"), 300)).statusCode()).isEqualTo(403);

        HttpResponse<String> admin = get("/api/admin/payment-faults", token(KEY, "01900000-0000-7000-8001-000000000100", List.of("ADMIN", "USER"), 300));
        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(LAST_REQUEST.get()).contains("path=/admin/payment-faults");

        assertThat(get("/api/admin/products", token(KEY, "01900000-0000-7000-8001-000000000009", List.of("USER"), 300)).statusCode()).isEqualTo(403);
        assertThat(get("/api/admin/products/01900000-0000-7000-8000-000000000001/price-history", token(KEY, "01900000-0000-7000-8001-000000000100", List.of("ADMIN", "USER"), 300))
                .statusCode()).isEqualTo(200);
        assertThat(LAST_REQUEST.get()).contains("path=/admin/products/01900000-0000-7000-8000-000000000001/price-history");

        // 재고 조정 (ADR 0019) → inventory-service
        String adjustments = "/api/admin/stocks/01900000-0000-7000-8000-000000000001/adjustments";
        assertThat(get(adjustments, token(KEY, "01900000-0000-7000-8001-000000000009", List.of("USER"), 300)).statusCode()).isEqualTo(403);
        assertThat(get(adjustments, token(KEY, "01900000-0000-7000-8001-000000000100", List.of("ADMIN", "USER"), 300))
                .statusCode()).isEqualTo(200);
        assertThat(LAST_REQUEST.get()).contains("path=/admin/stocks/01900000-0000-7000-8000-000000000001/adjustments");
    }

    @Test
    void 상품_조회는_토큰_없이_넘기고_기존_auth_경로는_없다() throws Exception {
        // 기존 자체 JWT API(/auth/**)는 후속 5-3c 에서 지웠다. 로그인은 SAS(auth-service 직접)와 BFF(/oauth2/authorization/**)
        LAST_REQUEST.set(null);
        assertThat(send(HttpRequest.newBuilder(uri("/auth/login"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build()).statusCode()).isIn(401, 403, 404);
        assertThat(LAST_REQUEST.get()).isNull();

        assertThat(get("/api/stocks/01900000-0000-7000-8000-000000000001", null).statusCode()).isEqualTo(200);
        assertThat(LAST_REQUEST.get()).contains("path=/stocks/01900000-0000-7000-8000-000000000001");

        assertThat(get("/api/products?ids=01900000-0000-7000-8000-000000000001,01900000-0000-7000-8000-000000000002", null).statusCode()).isEqualTo(200);
        assertThat(LAST_REQUEST.get()).contains("path=/products");

        assertThat(get("/api/search/products?q=key", null).statusCode()).isEqualTo(200);
        assertThat(LAST_REQUEST.get()).contains("path=/search/products");
    }

    @Test
    void 정해진_경로가_아니면_거부() throws Exception {
        assertThat(get("/internal/anything", token(KEY, "01900000-0000-7000-8001-000000000007", List.of("USER"), 300)).statusCode()).isIn(403, 404);
        assertThat(get("/orders/abc", null).statusCode()).isEqualTo(401);
    }

    @Test
    void 사용자별_rate_limit_을_넘으면_429_이고_다른_사용자는_영향이_없다() throws Exception {
        String busy = token(KEY, "01900000-0000-7000-8001-000000000021", List.of("USER"), 300);
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            if (get("/api/points/me", busy).statusCode() == 429) {
                limited++;
            }
        }

        assertThat(limited).isPositive();
        assertThat(get("/api/points/me", token(KEY, "01900000-0000-7000-8001-000000000022", List.of("USER"), 300)).statusCode()).isEqualTo(200);
    }

    @Test
    void 로그인_없는_요청의_rate_limit_은_믿는_프록시가_넘긴_브라우저_IP_마다_따로다() throws Exception {
        // 테스트 클라이언트는 localhost 라 기본 설정(gateway.trusted-proxies = 루프백)에서 앞단 프록시처럼 믿는 주소다
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            if (stocksFrom("198.51.100.1").statusCode() == 429) {
                limited++;
            }
        }

        assertThat(limited).isPositive();
        // 같은 프록시를 거친 다른 브라우저는 영향이 없다
        assertThat(stocksFrom("198.51.100.2").statusCode()).isNotEqualTo(429);
    }

    private HttpResponse<String> stocksFrom(String browserIp) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/stocks/01900000-0000-7000-8000-000000000001")).header("X-Forwarded-For", browserIp).GET().build());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String token(RSAKey key, String subject, List<String> roles, long ttlSeconds) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(subject).claim("roles", roles)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(ttlSeconds))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey rsaKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // JWKS 는 KEY 의 공개키만 낸다. 나머지 경로는 받은 경로·헤더를 기록하고 200
    private static HttpServer downstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body;
                if (exchange.getRequestURI().getPath().equals("/oauth2/jwks")) {
                    body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                } else {
                    LAST_REQUEST.set("path=" + exchange.getRequestURI().getPath()
                            + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                            + " x-user-id=" + exchange.getRequestHeaders().getFirst("X-User-Id"));
                    body = "{}".getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
