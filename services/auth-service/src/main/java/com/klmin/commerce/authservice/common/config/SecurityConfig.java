package com.klmin.commerce.authservice.common.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Set;

/**
 * <b>기존 자체 JWT API</b> ({@code /auth/**}, {@code /.well-known/jwks.json}, ADR 0008) 의 보안 설정.
 * 지금 Next 화면이 쓰므로 그대로 두고, Next 를 지운 뒤(5-3b) 후속 5-3c 에서 지운다. SAS·로그인 화면은 {@link AuthorizationServerConfig}.
 * <p>
 * 회원가입·로그인·refresh·로그아웃·JWKS 는 토큰 없이, 로그인 기기 관리·전체 로그아웃은 access token 으로.
 * 검증 키는 auth-service 자신의 공개키 (JwtDecoder 는 adapter.out.security 의 JwtKeyConfig). 세션·폼 로그인을 쓰지 않는다.
 * <p>
 * CSRF: 쿠키로 인증되는 요청은 refresh·로그아웃뿐이다(나머지는 Authorization 헤더로만 인증). 이 둘은 두 겹으로 막는다.
 * 쿠키가 {@code SameSite=Lax} 라 다른 사이트에서 보낸 POST 에 실리지 않고, 요청에 {@value #CSRF_HEADER} 헤더가 있어야 한다.
 * 다른 사이트의 폼은 헤더를 붙일 수 없고, 다른 출처의 fetch 가 헤더를 붙이면 CORS preflight 에서 막힌다(CORS 를 열지 않음).
 * 헤더가 없으면 Spring Security CSRF 검사로 보내 토큰이 없으니 403 이 된다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** refresh·로그아웃에 필요한 헤더. 값은 상관없다 (Next 서버는 {@code fetch}) */
    public static final String CSRF_HEADER = "X-Requested-With";

    private static final Set<String> COOKIE_AUTHENTICATED = Set.of("/auth/refresh", "/auth/logout");

    // SAS 체인(1) 다음, 로그인 화면 체인(3) 앞. 경로가 /auth/** 로 좁혀져 있어 다른 체인과 겹치지 않는다
    @Bean
    @Order(2)
    SecurityFilterChain legacyApiFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/auth/**", "/.well-known/jwks.json")
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(SecurityConfig::cookieRequestWithoutHeader))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/signup", "/auth/login", "/auth/refresh", "/auth/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())))
                .build();
    }

    private static boolean cookieRequestWithoutHeader(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && COOKIE_AUTHENTICATED.contains(request.getRequestURI())
                && request.getHeader(CSRF_HEADER) == null;
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
