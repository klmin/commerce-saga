package com.klmin.commerce.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.logout.DelegatingServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.SecurityContextServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.WebSessionServerLogoutHandler;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Gateway 의 인증 (ADR 0008 의 1차 검증 + ADR 0017 의 BFF). 서비스가 다시 검증한다.
 * <p>
 * <b>두 가지 인증을 함께 받는다.</b>
 * <ul>
 *   <li><b>BFF 세션</b>(화면 SPA): 쿠키 {@code GW_SESSION}. 로그인은 {@code /oauth2/authorization/auto} → SAS → 콜백
 *       {@code /login/oauth2/code/*}. 토큰은 세션에 있고 {@code TokenRelay} 가 서비스로 {@code Authorization: Bearer} 를 붙인다</li>
 *   <li><b>Bearer</b>: {@code Authorization} 헤더의 access token 을 JWKS 로 검증 (시나리오 스크립트, 나중 모바일·외부 앱)</li>
 * </ul>
 * 경로 규칙:
 * <ul>
 *   <li>{@code GET /api/stocks/**}, {@code GET /api/products/**}, {@code GET /api/search/**}: 로그인 없이</li>
 *   <li>{@code /api/admin/**}: ADMIN 역할(세션은 ID 토큰의 roles, Bearer 는 access token 의 roles)</li>
 *   <li>그 밖의 {@code /api/**}: 로그인. 없으면 리다이렉트가 아니라 <b>401</b>(SPA 가 로그인 시작 주소로 화면을 옮긴다)</li>
 * </ul>
 * <b>CSRF</b>: 쿠키가 실린 상태 변경 요청만 검사한다(Bearer 만 쓰는 요청은 브라우저가 자동으로 싣는 자격 증명이 없다).
 * 토큰은 쿠키 {@code XSRF-TOKEN}(JS 가 읽음) → SPA 가 헤더 {@code X-XSRF-TOKEN} 또는 폼 {@code _csrf} 로 돌려보낸다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class GatewaySecurityConfig {

    private static final Set<HttpMethod> SAFE_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
            HttpMethod.TRACE);

    @Bean
    BffLoginFlow bffLoginFlow(BffProperties properties, ReactiveClientRegistrationRepository registrations) {
        return new BffLoginFlow(properties, registrations);
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, BffLoginFlow flow,
                                                  ServerOAuth2AuthorizedClientRepository authorizedClients) {
        CookieServerCsrfTokenRepository csrfTokens = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookiePath("/");
        return http
                .csrf(csrf -> csrf
                        .requireCsrfProtectionMatcher(GatewaySecurityConfig::stateChangeWithCookie)
                        .csrfTokenRepository(csrfTokens)
                        // SPA 는 쿠키 값을 그대로 헤더에 넣는다(마스킹하지 않는 처리기)
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/stocks/**", "/api/products", "/api/products/**", "/api/search/**").permitAll()
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2Login(login -> login
                        .authorizationRequestResolver(flow.authorizationRequestResolver())
                        .authorizedClientRepository(authorizedClients)
                        .authenticationSuccessHandler(flow.successHandler())
                        .authenticationFailureHandler(flow.failureHandler()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutHandler(new DelegatingServerLogoutHandler(new SecurityContextServerLogoutHandler(),
                                new WebSessionServerLogoutHandler()))
                        .logoutSuccessHandler(flow.logoutSuccessHandler()))
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())))
                // 화면이 아니라 API 라 로그인 화면으로 보내지 않는다
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterAfter(csrfCookie(), SecurityWebFiltersOrder.CSRF)
                .build();
    }

    // CSRF 토큰은 누가 구독해야 쿠키로 저장된다. 응답마다 구독해 SPA 가 첫 GET 부터 XSRF-TOKEN 을 받게 한다
    private static WebFilter csrfCookie() {
        return (exchange, chain) -> {
            Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
            return (token == null ? Mono.<CsrfToken>empty() : token).then(chain.filter(exchange));
        };
    }

    private static Mono<ServerWebExchangeMatcher.MatchResult> stateChangeWithCookie(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        boolean check = !SAFE_METHODS.contains(request.getMethod())
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
