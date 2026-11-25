package com.klmin.commerce.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.AuthorizationCodeReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.DelegatingReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.RefreshTokenReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gateway 가 SAS 의 OAuth2 클라이언트가 되는 설정 (ADR 0017, 설계 문서 6·7-3절).
 * <ul>
 *   <li>클라이언트 등록은 코드로 한다. discovery({@code issuer-uri})를 쓰지 않는다: 컨테이너 안에서 공개 주소가 자기 자신을
 *       가리키므로 브라우저가 가는 주소(공개 {@code issuer})와 Gateway 가 부르는 주소(내부 {@code internal-url})를 나눈다.
 *       discovery 가 없으면 OIDC 로그아웃 주소({@code end_session_endpoint})도 빠지므로 메타데이터로 직접 넣는다</li>
 *   <li>토큰은 세션(WebSession = Spring Session Redis)에 둔다. 기본(메모리)이면 인스턴스마다 달라 다른 Pod 로 간 요청이 토큰을 못 찾는다</li>
 *   <li>access token 이 끝나면 refresh 로 자동 갱신한다(TokenRelay 가 요청 때). 갱신이 거절되면 토큰을 지우고 API 는 401</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class BffClientConfig {

    @Bean
    ReactiveClientRegistrationRepository clientRegistrationRepository(BffProperties properties) {
        return new InMemoryReactiveClientRegistrationRepository(
                registration(BffProperties.WEB, properties.web(), properties),
                registration(BffProperties.ADMIN, properties.admin(), properties));
    }

    private static ClientRegistration registration(String id, BffProperties.Client client, BffProperties properties) {
        String secret = ClientSecretSource.resolve(id, client.secret(), properties.secretDevDir());
        return ClientRegistration.withRegistrationId(id)
                .clientId(id)
                .clientSecret(secret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                // 설정값 그대로 (auth-service 에 등록된 값과 정확히 같다). 요청 주소로 만들면 전달 헤더를 믿어야 한다
                .redirectUri(client.redirectUri())
                .scope(OidcScopes.OPENID)
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .issuerUri(properties.issuer())
                .authorizationUri(properties.issuer() + "/oauth2/authorize")
                .tokenUri(properties.internalUrl() + "/oauth2/token")
                .jwkSetUri(properties.internalUrl() + "/oauth2/jwks")
                .userNameAttributeName("sub")
                // 브라우저가 가는 주소라 공개 주소
                .providerConfigurationMetadata(Map.of("end_session_endpoint", properties.issuer() + "/connect/logout"))
                .build();
    }

    @Bean
    ServerOAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new WebSessionServerOAuth2AuthorizedClientRepository();
    }

    @Bean
    ReactiveOAuth2AuthorizedClientManager authorizedClientManager(ReactiveClientRegistrationRepository registrations,
                                                                  ServerOAuth2AuthorizedClientRepository authorizedClients) {
        DefaultReactiveOAuth2AuthorizedClientManager manager =
                new DefaultReactiveOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(new DelegatingReactiveOAuth2AuthorizedClientProvider(
                new AuthorizationCodeReactiveOAuth2AuthorizedClientProvider(),
                new RefreshTokenReactiveOAuth2AuthorizedClientProvider()));
        return manager;
    }

    /**
     * 로그인 사용자 = ID 토큰. 이름은 {@code sub}(userId), 권한은 ID 토큰의 {@code roles} → {@code ROLE_*}
     * ({@code /api/admin/**} 의 1차 검사용. 서비스는 relay 된 access token 으로 다시 검사한다).
     * userinfo 주소를 등록하지 않아 userinfo 는 부르지 않는다(필요한 값은 ID 토큰에 다 있다)
     */
    @Bean
    ReactiveOAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcReactiveOAuth2UserService delegate = new OidcReactiveOAuth2UserService();
        return request -> delegate.loadUser(request).map(user -> {
            List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
            List<String> roles = user.getIdToken().getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }
            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "sub");
        });
    }

    /**
     * BFF 세션 쿠키. auth-service 의 {@code AUTH_SESSION} 과 이름을 나눈다(로컬은 포트만 다른 localhost 라 섞인다).
     * HttpOnly(JS 가 못 읽음), SameSite=Lax, Secure 는 https 에서만(로컬·compose 는 http)
     */
    @Bean
    WebSessionIdResolver webSessionIdResolver(@Value("${gateway.bff.cookie-secure:true}") boolean secure) {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.setCookieName("GW_SESSION");
        resolver.addCookieInitializer((ResponseCookie.ResponseCookieBuilder cookie) ->
                cookie.httpOnly(true).sameSite("Lax").secure(secure).path("/"));
        return resolver;
    }
}
