package com.klmin.commerce.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Gateway 의 1차 검증 (ADR 0008). 서비스가 다시 검증하지만, 토큰 없는·위조된 요청은 여기서 401 로 끝내 서비스까지 가지 않는다.
 * <ul>
 *   <li>{@code /auth/**}, JWKS: 토큰 없이 auth-service 로 (로그인 전 요청)</li>
 *   <li>{@code GET /api/stocks/**}: 토큰 없이 (로그인 전 상품 화면)</li>
 *   <li>{@code /api/admin/**}: ADMIN 역할</li>
 *   <li>그 밖의 {@code /api/**}: 유효한 access token. 경로에 없는 것은 모두 거부</li>
 * </ul>
 * 검증 키는 auth-service JWKS (application.yml {@code spring.security.oauth2.resourceserver.jwt}).
 * <p>
 * CSRF 는 쿠키처럼 브라우저가 자동으로 싣는 자격 증명을 노린다. {@code /api/**} 는 Authorization 헤더로만 인증하므로
 * 쿠키가 실린 상태 변경 요청에만 검사를 건다. {@code /auth/**} 의 쿠키 요청(refresh·로그아웃)은 auth-service 가 직접 막는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class GatewaySecurityConfig {

    private static final Set<HttpMethod> SAFE_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
            HttpMethod.TRACE);

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(GatewaySecurityConfig::apiStateChangeWithCookie))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/auth/**", "/.well-known/jwks.json").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/stocks/**").permitAll()
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())))
                .build();
    }

    private static Mono<ServerWebExchangeMatcher.MatchResult> apiStateChangeWithCookie(
            org.springframework.web.server.ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        boolean check = !SAFE_METHODS.contains(request.getMethod())
                && !request.getPath().value().startsWith("/auth/")
                && request.getHeaders().containsHeader(HttpHeaders.COOKIE);
        return check ? ServerWebExchangeMatcher.MatchResult.match() : ServerWebExchangeMatcher.MatchResult.notMatch();
    }

    // roles 클레임 → ROLE_USER, ROLE_ADMIN
    private static ReactiveJwtAuthenticationConverterAdapter rolesConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
