package com.klmin.commerce.authservice.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 회원가입·로그인·refresh·로그아웃·JWKS 는 토큰 없이, 로그인 기기 관리·전체 로그아웃은 access token 으로 (ADR 0008).
 * 검증 키는 auth-service 자신의 공개키 (JwtDecoder 는 adapter.out.security 의 JwtKeyConfig).
 * <p>
 * 세션·폼 로그인을 쓰지 않는다. CSRF 토큰도 쓰지 않는다: 상태를 바꾸는 요청 중 쿠키로 인증되는 것은 refresh·로그아웃뿐이고,
 * 그 쿠키는 {@code SameSite=Lax} 라 다른 사이트에서 보낸 POST 에 실리지 않는다. 나머지는 Authorization 헤더로만 인증한다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/signup", "/auth/login", "/auth/refresh", "/auth/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())))
                .build();
    }

    // roles 클레임 → ROLE_USER, ROLE_ADMIN
    private static JwtAuthenticationConverter rolesConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
