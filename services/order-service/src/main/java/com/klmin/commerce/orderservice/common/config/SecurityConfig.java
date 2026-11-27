package com.klmin.commerce.orderservice.common.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Set;
import java.util.UUID;

/**
 * access token(JWT) 검증 (ADR 0008). Gateway 가 이미 검증했어도 내부망을 무조건 믿지 않고 여기서 다시 검증한다.
 * 공개키는 auth-service JWKS ({@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}), 역할은 {@code roles} 클레임.
 * 사용자는 토큰의 {@code sub} 이고({@code CurrentUserId}), 리소스 소유권은 지금처럼 서비스 안에서 비교한다.
 * {@code sub} 는 사용자 id 의 UUID 표준 표기(소문자)여야 한다. 아니면 잘못된 토큰으로 보고 401 (ADR 0018).
 * <p>
 * CSRF 는 쿠키처럼 브라우저가 자동으로 싣는 자격 증명을 노린다. 이 서비스는 Authorization 헤더로만 인증하므로
 * 쿠키가 실린 상태 변경 요청에만 CSRF 검사를 건다(토큰이 없으니 403). 세션은 만들지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(SecurityConfig::stateChangeWithCookie))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/error")
                        .permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .build();
    }

    /** 자동 구성된 JwtDecoder 가 기본 검증(서명·만료)에 더해 이 검증기도 건다 (Spring Boot additional validators) */
    @Bean
    OAuth2TokenValidator<Jwt> userIdSubjectValidator() {
        return SecurityConfig::validateSubject;
    }

    static OAuth2TokenValidatorResult validateSubject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject != null) {
            try {
                if (UUID.fromString(subject).toString().equals(subject)) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (IllegalArgumentException e) {
                // 아래에서 실패로 응답
            }
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "sub must be a user id (UUID)", null));
    }

    private static boolean stateChangeWithCookie(HttpServletRequest request) {
        return !SAFE_METHODS.contains(request.getMethod()) && request.getHeader(HttpHeaders.COOKIE) != null;
    }
}
