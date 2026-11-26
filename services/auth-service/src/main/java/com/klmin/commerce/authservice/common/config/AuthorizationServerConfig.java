package com.klmin.commerce.authservice.common.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.oidc.web.authentication.OidcLogoutAuthenticationSuccessHandler;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.util.Map;

/**
 * OIDC 인가 서버(Spring Authorization Server)와 로그인 화면의 보안 설정 (ADR 0017, 설계 문서 4·7절). 필터 체인 둘 (순서 1·3. 2번이던 기존 자체 JWT API 는 후속 5-3c 에서 지웠다):
 * <ol>
 *   <li><b>SAS 표준 엔드포인트</b> ({@code /oauth2/*}, {@code /.well-known/openid-configuration}, {@code /userinfo},
 *       {@code /connect/logout}). 로그인이 안 된 브라우저는 로그인 화면 {@code /signin} 으로</li>
 *   <li><b>로그인·가입 처리</b>: 처리 {@code POST /login}·{@code POST /signup},
 *       CSRF 토큰 {@code GET /csrf}. 화면 {@code /signin}·{@code /register} 는 인증 화면 SPA({@code frontend/apps/auth}, 후속 5-4)가
 *       그리고 앞단 프록시가 그쪽으로 보낸다. SSO 로그인 세션(Spring Session Redis, 쿠키 {@code AUTH_SESSION})에 로그인 상태와
 *       CSRF 토큰을 둔다</li>
 * </ol>
 * 주소는 앱 기준 경로다. 경로 방식({@code example.com/auth/...})이면 앞단 프록시가 {@code /auth} 를 떼고
 * {@code X-Forwarded-Prefix} 를 붙이며, {@code forward-headers-strategy=framework} 가 리다이렉트·쿠키 경로에 다시 붙인다.
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerFilterChain(HttpSecurity http, JWKSource<SecurityContext> jwkSource,
                                                       LogoutHandler ssoLogoutHandler,
                                                       AuthorizationServerSettings settings) throws Exception {
        OidcLogoutAuthenticationSuccessHandler logoutSuccess = new OidcLogoutAuthenticationSuccessHandler();
        // 인가 기록을 먼저 지우고(로그인 사용자가 필요) 세션을 끝낸다
        logoutSuccess.setLogoutHandler(new CompositeLogoutHandler(ssoLogoutHandler, new SecurityContextLogoutHandler()));
        NimbusJwtDecoder userInfoDecoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        userInfoDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(settings.getIssuer()));
        http
                .oauth2AuthorizationServer(as -> {
                    http.securityMatcher(as.getEndpointsMatcher());
                    as.oidc(oidc -> oidc.logoutEndpoint(logout -> logout.logoutResponseHandler(logoutSuccess)));
                })
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // 브라우저(HTML)는 로그인 화면으로. 주소는 앱 기준이라 경로 방식에서는 앞 경로가 붙는다
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/signin"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // /userinfo 는 SAS 가 발급한 access token 으로
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.decoder(userInfoDecoder)));
        return http.build();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(@Value("${auth.issuer}") String issuer) {
        // 토큰 iss·discovery 의 기준. 끝에 / 를 붙이지 않는다(검증하는 쪽은 문자열 그대로 비교한다)
        if (issuer.endsWith("/")) {
            throw new IllegalStateException("auth.issuer(AUTH_ISSUER) 는 / 로 끝나면 안 된다: " + issuer);
        }
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbc,
                                                                  RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    /**
     * SSO 로그인 세션 쿠키. 이름을 Gateway 의 {@code GW_SESSION} 과 나눈다(로컬 개발은 포트만 다른 localhost 라 이름이 같으면
     * 서로 덮어쓴다). 경로는 요청의 context path(경로 방식이면 {@code X-Forwarded-Prefix} 의 {@code /auth})를 따른다.
     * Boot 의 {@code server.servlet.session.cookie.*} 는 내장 서버로 뜰 때만 적용되므로 코드로 둔다
     */
    @Bean
    CookieSerializer cookieSerializer(@Value("${auth.cookie.secure}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("AUTH_SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secure);
        return serializer;
    }

    /** 클라이언트 시크릿 해시 ({@code {bcrypt}…}). 회원 비밀번호는 adapter 의 {@code PasswordHasher} 가 따로 다룬다 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 로그인 처리 {@code POST /login}(폼, {@code _csrf}). 성공하면 저장해 둔 인가 요청으로(인가 흐름이 이어진다),
     * 없으면 사용자 화면으로. 실패하면 {@code /signin?error}, 잠기면 {@code /signin?error=locked}.
     * CSRF 토큰은 세션에 두고 쿠키로 내보내지 않는다(Gateway 의 {@code XSRF-TOKEN} 쿠키와 겹치지 않게, 설계 문서 4절).
     * 로그아웃은 OIDC 로그아웃({@code /connect/logout}, 1번 체인)만 쓴다.
     * 아이디·비밀번호 확인은 {@code AuthenticationProvider} 빈(전역 AuthenticationManager)이 한다. 체인에 다시 등록하면
     * 체인 것이 실패한 뒤 전역 것이 한 번 더 확인해 실패가 두 번 세어진다
     */
    @Bean
    @Order(3)
    SecurityFilterChain loginFilterChain(HttpSecurity http,
                                         @Value("${auth.web-home}") String webHome) throws Exception {
        SavedRequestAwareAuthenticationSuccessHandler success = new SavedRequestAwareAuthenticationSuccessHandler();
        success.setDefaultTargetUrl(webHome);
        ExceptionMappingAuthenticationFailureHandler failure = new ExceptionMappingAuthenticationFailureHandler();
        failure.setDefaultFailureUrl("/signin?error");
        failure.setExceptionMappings(Map.of(LockedException.class.getName(), "/signin?error=locked"));
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/signup").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/signin")
                        .loginProcessingUrl("/login")
                        .successHandler(success)
                        .failureHandler(failure)
                        .permitAll())
                .logout(logout -> logout.disable())
                .httpBasic(basic -> basic.disable())
                .build();
    }
}
