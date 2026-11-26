package com.klmin.commerce.authservice.auth.adapter.out.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * 시작할 때 설정의 클라이언트를 DB({@code oauth2_registered_client})에 맞춘다 (설계 문서 6절, ADR 0017). 멱등이다.
 * <ul>
 *   <li>없으면 등록(시크릿은 {@link PasswordEncoder} 해시)</li>
 *   <li>있으면 {@code matches(원문, 해시)} 로 비교해 다르면 해시를 바꾼다(시크릿 교체). 해시끼리 문자열로 비교하지 않는다
 *       (BCrypt 는 매번 해시가 다르다)</li>
 *   <li>redirect URI·로그아웃 뒤 주소·scope·토큰 설정도 설정과 다르면 바꾼다. 환경을 바꿔도 DB 를 손대지 않는다</li>
 *   <li>인스턴스 여럿이 동시에 처음 뜨면 둘 다 INSERT 할 수 있다 → 키 충돌이면 다시 읽어 한 번 더 맞춘다</li>
 * </ul>
 * 설정에 없는 클라이언트는 지우지 않는다(다른 경로로 등록한 클라이언트를 건드리지 않게).
 */
@Component
class RegisteredClientSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RegisteredClientSync.class);

    private final RegisteredClientRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final OAuthClientProperties properties;
    private final Clock clock;

    RegisteredClientSync(RegisteredClientRepository repository, PasswordEncoder passwordEncoder,
                         OAuthClientProperties properties, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Map.Entry<String, OAuthClientProperties.Client> entry : properties.clients().entrySet()) {
            String clientId = entry.getKey();
            String secret = ClientSecretSource.resolve(clientId, entry.getValue().secret(), properties.secretDevDir());
            try {
                sync(clientId, entry.getValue(), secret);
            } catch (DuplicateKeyException e) {
                // 다른 인스턴스가 먼저 등록했다. 그 행을 기준으로 한 번 더 맞춘다
                sync(clientId, entry.getValue(), secret);
            }
        }
    }

    void sync(String clientId, OAuthClientProperties.Client config, String rawSecret) {
        RegisteredClient existing = repository.findByClientId(clientId);
        if (config.redirectUris().isEmpty()) {
            throw new IllegalStateException("클라이언트 " + clientId + " 의 redirect URI 가 없다");
        }
        String secretHash = existing != null && existing.getClientSecret() != null
                && passwordEncoder.matches(rawSecret, existing.getClientSecret())
                ? existing.getClientSecret()
                : passwordEncoder.encode(rawSecret);
        RegisteredClient desired = RegisteredClient.withId(existing != null ? existing.getId() : clientId)
                .clientId(clientId)
                .clientName(clientId)
                .clientIdIssuedAt(existing != null ? existing.getClientIdIssuedAt() : clock.instant())
                .clientSecret(secretHash)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(config.redirectUris()))
                .postLogoutRedirectUris(uris -> uris.addAll(config.postLogoutRedirectUris()))
                .scope(OidcScopes.OPENID)
                // 1st-party 클라이언트라 동의 화면을 끈다. PKCE 는 confidential 클라이언트에도 요구한다(인가 코드 가로채기 방지)
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .refreshTokenTimeToLive(properties.refreshTokenTtl())
                        // 회전하지 않는다. refresh token 은 브라우저에 나가지 않고 Gateway 세션에만 있어 회전으로 탐지할 것이 없고,
                        // 회전하면 같은 세션의 동시 요청이 옛 토큰으로 갱신하다 거절돼 세션이 끝난다(Gateway 여러 대여도 같다).
                        // 수명은 로그인 때부터 refreshTokenTtl 로 고정된다(절대 상한)
                        .reuseRefreshTokens(true)
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                        .build())
                .build();
        if (existing == null) {
            repository.save(desired);
            log.info("OAuth 클라이언트 등록: {} {}", clientId, config);
        } else if (!sameSettings(existing, desired) || !Objects.equals(existing.getClientSecret(), secretHash)) {
            repository.save(desired);
            log.info("OAuth 클라이언트 갱신: {} {} (시크릿 {})", clientId, config,
                    Objects.equals(existing.getClientSecret(), secretHash) ? "그대로" : "교체");
        }
    }

    private static boolean sameSettings(RegisteredClient a, RegisteredClient b) {
        return a.getClientAuthenticationMethods().equals(b.getClientAuthenticationMethods())
                && a.getAuthorizationGrantTypes().equals(b.getAuthorizationGrantTypes())
                && a.getRedirectUris().equals(b.getRedirectUris())
                && a.getPostLogoutRedirectUris().equals(b.getPostLogoutRedirectUris())
                && a.getScopes().equals(b.getScopes())
                && a.getClientSettings().equals(b.getClientSettings())
                && a.getTokenSettings().equals(b.getTokenSettings());
    }
}
