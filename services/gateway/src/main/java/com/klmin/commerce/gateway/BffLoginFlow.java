package com.klmin.commerce.gateway;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * BFF 로그인·로그아웃 흐름 (ADR 0017, 설계 문서 7-3 "로그인 뒤 돌아갈 곳·클라이언트 고르기·로그아웃 뒤 돌아갈 곳").
 * <ul>
 *   <li>화면은 클라이언트 이름을 모르고 {@code /oauth2/authorization/auto?return_to=<경로>} 로 로그인을 시작한다
 *       (같은 빌드가 호스트·경로 방식에서 돌아야 하므로). 요청 호스트가 {@code adminClientHost} 면 {@code gateway-admin},
 *       아니면 {@code gateway-web}</li>
 *   <li>{@code return_to} 는 같은 출처의 경로만 받아 세션에 두고, 로그인이 끝나면 그리로 보낸다. 없으면 그 클라이언트의 홈.
 *       API 요청은 401 만 받으므로 Spring 의 저장된 요청(request cache)에 기대지 않는다</li>
 *   <li>로그아웃({@code POST /logout}, 폼 {@code return_to})은 BFF 세션을 지운 뒤 SAS 의 OIDC 로그아웃으로 보내 SSO 세션까지
 *       끝낸다. 돌아올 주소는 허용된 홈 둘({@code webHome}, {@code adminHome}) 중 하나만(SAS 에 등록된 절대 주소)</li>
 * </ul>
 */
final class BffLoginFlow {

    static final String AUTO = "auto";
    static final String RETURN_TO = "return_to";
    private static final String SESSION_RETURN_TO = BffLoginFlow.class.getName() + ".RETURN_TO";

    private final BffProperties properties;
    private final ReactiveClientRegistrationRepository registrations;
    private final ServerRedirectStrategy redirects = new DefaultServerRedirectStrategy();

    BffLoginFlow(BffProperties properties, ReactiveClientRegistrationRepository registrations) {
        this.properties = properties;
        this.registrations = registrations;
    }

    // ---- 로그인 시작 ----

    ServerOAuth2AuthorizationRequestResolver authorizationRequestResolver() {
        DefaultServerOAuth2AuthorizationRequestResolver delegate =
                new DefaultServerOAuth2AuthorizationRequestResolver(registrations);
        ServerWebExchangeMatcher start = new PathPatternParserServerWebExchangeMatcher(
                DefaultServerOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_PATTERN);
        return new ServerOAuth2AuthorizationRequestResolver() {
            @Override
            public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange) {
                return start.matches(exchange)
                        .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
                        .flatMap(match -> {
                            String requested = (String) match.getVariables()
                                    .get(DefaultServerOAuth2AuthorizationRequestResolver.DEFAULT_REGISTRATION_ID_URI_VARIABLE_NAME);
                            String registrationId = AUTO.equals(requested) ? chooseClient(exchange) : requested;
                            return rememberReturnTo(exchange).then(delegate.resolve(exchange, registrationId));
                        });
            }

            @Override
            public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange, String clientRegistrationId) {
                return delegate.resolve(exchange, clientRegistrationId);
            }
        };
    }

    /**
     * 요청 주소의 호스트(포트 포함)로 고른다. 앞단 프록시·Vite 는 원래 Host 를 넘기고, 믿는 프록시의 X-Forwarded-Host 는
     * {@link TrustedForwardedHeadersFilter} 가 이미 요청 주소에 반영했다
     */
    String chooseClient(ServerWebExchange exchange) {
        if (properties.adminClientHost().isEmpty()) {
            return BffProperties.WEB;
        }
        URI uri = exchange.getRequest().getURI();
        String authority = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        return authority.equals(properties.adminClientHost()) ? BffProperties.ADMIN : BffProperties.WEB;
    }

    private Mono<Void> rememberReturnTo(ServerWebExchange exchange) {
        Optional<String> returnTo = safePath(exchange.getRequest().getQueryParams().getFirst(RETURN_TO));
        return exchange.getSession().doOnNext(session -> {
            if (returnTo.isPresent()) {
                session.getAttributes().put(SESSION_RETURN_TO, returnTo.get());
            } else {
                session.getAttributes().remove(SESSION_RETURN_TO);
            }
        }).then();
    }

    /**
     * 같은 출처의 경로만 (open redirect 방지). {@code /} 로 시작하고, {@code //}(다른 호스트)·역슬래시·제어문자·공백이 없어야 한다.
     * 브라우저 URL 파서는 탭·줄바꿈을 지우고 역슬래시를 {@code /} 로 읽어 {@code /\evil.example} 이 외부 주소가 된다
     */
    static Optional<String> safePath(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048 || value.charAt(0) != '/') {
            return Optional.empty();
        }
        if (value.startsWith("//")) {
            return Optional.empty();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c <= 0x20 || c == 0x7f) {
                return Optional.empty();
            }
        }
        return Optional.of(value);
    }

    // ---- 로그인 성공 ----

    ServerAuthenticationSuccessHandler successHandler() {
        return (WebFilterExchange webExchange, Authentication authentication) -> {
            ServerWebExchange exchange = webExchange.getExchange();
            String registrationId = registrationId(authentication);
            return exchange.getSession().flatMap(session -> {
                Object saved = session.getAttributes().remove(SESSION_RETURN_TO);
                String target = saved instanceof String path ? path : home(registrationId);
                return redirects.sendRedirect(exchange, URI.create(target));
            });
        };
    }

    /**
     * 로그인 실패(state 불일치·토큰 교환 실패 등): 사용자 화면으로 {@code login_error} 를 붙여 보낸다.
     * 기본({@code /login?error})은 Gateway 경로가 아니라 화면에 없는 주소다
     */
    ServerAuthenticationFailureHandler failureHandler() {
        return (webExchange, exception) -> redirects.sendRedirect(webExchange.getExchange(),
                URI.create(properties.webHome() + (properties.webHome().contains("?") ? "&" : "?") + "login_error"));
    }

    // ---- 로그아웃 ----

    /**
     * SSO 로그아웃: SAS {@code end_session_endpoint} 로 {@code id_token_hint}·{@code post_logout_redirect_uri} 를 붙여 보낸다.
     * OIDC 로 로그인한 세션이 아니면(Bearer 등) 홈으로
     */
    ServerLogoutSuccessHandler logoutSuccessHandler() {
        return (WebFilterExchange webExchange, Authentication authentication) -> {
            ServerWebExchange exchange = webExchange.getExchange();
            String registrationId = registrationId(authentication);
            return exchange.getFormData()
                    .map(form -> safePath(form.getFirst(RETURN_TO)))
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(returnTo -> registrations.findByRegistrationId(registrationId).flatMap(registration -> {
                        String postLogout = absolute(logoutHome(registrationId, returnTo.orElse(null)), registration);
                        Object endSession = registration.getProviderDetails().getConfigurationMetadata()
                                .get("end_session_endpoint");
                        if (!(authentication instanceof OAuth2AuthenticationToken token)
                                || !(token.getPrincipal() instanceof OidcUser user) || endSession == null) {
                            return redirects.sendRedirect(exchange, URI.create(postLogout));
                        }
                        URI target = UriComponentsBuilder.fromUriString(endSession.toString())
                                .queryParam("id_token_hint", user.getIdToken().getTokenValue())
                                .queryParam("post_logout_redirect_uri", postLogout)
                                .encode().build().toUri();
                        return redirects.sendRedirect(exchange, target);
                    }));
        };
    }

    private static String registrationId(Authentication authentication) {
        return authentication instanceof OAuth2AuthenticationToken token
                ? token.getAuthorizedClientRegistrationId() : BffProperties.WEB;
    }

    String home(String registrationId) {
        return BffProperties.ADMIN.equals(registrationId) ? properties.adminHome() : properties.webHome();
    }

    /**
     * 경로 방식은 관리자 화면도 {@code gateway-web} 이라 클라이언트로 홈을 정할 수 없다 → 화면이 보낸 {@code return_to} 가
     * 관리자 홈 경로 아래면 관리자 홈. 그 밖의 값은 받지 않는다(SAS 에는 등록된 정확한 주소만 된다)
     */
    String logoutHome(String registrationId, String returnTo) {
        if (BffProperties.ADMIN.equals(registrationId)) {
            return properties.adminHome();
        }
        String adminHome = properties.adminHome();
        if (returnTo != null && adminHome.startsWith("/") && returnTo.startsWith(adminHome)) {
            return adminHome;
        }
        return properties.webHome();
    }

    /** 경로로 준 홈은 그 클라이언트 redirect URI 의 출처를 붙여 절대 주소로 (SAS 의 post_logout_redirect_uri 는 절대 주소) */
    static String absolute(String home, ClientRegistration registration) {
        if (home.startsWith("http://") || home.startsWith("https://")) {
            return home;
        }
        URI redirect = URI.create(registration.getRedirectUri());
        String origin = redirect.getScheme() + "://" + redirect.getRawAuthority();
        return origin + home;
    }
}
