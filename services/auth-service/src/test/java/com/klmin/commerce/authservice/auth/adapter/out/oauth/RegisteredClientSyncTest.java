package com.klmin.commerce.authservice.auth.adapter.out.oauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 클라이언트 시크릿 찾는 순서와 시작할 때 DB 에 맞추는 규칙 (설계 문서 6절) */
class RegisteredClientSyncTest {

    private final InMemoryClients repository = new InMemoryClients();
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @TempDir
    Path dir;

    @Test
    void 외부에서_준_시크릿이_있으면_그_값() {
        assertThat(ClientSecretSource.resolve("gateway-web", " given ", dir.toString())).isEqualTo("given");
        assertThat(dir.resolve("oauth-client-gateway-web.secret")).doesNotExist();
    }

    @Test
    void 없으면_개발용_파일을_만들고_다음에는_같은_값을_읽는다() throws Exception {
        String first = ClientSecretSource.resolve("gateway-web", "", dir.toString());
        String second = ClientSecretSource.resolve("gateway-web", null, dir.toString());
        assertThat(first).hasSizeGreaterThanOrEqualTo(43).isEqualTo(second);
        assertThat(Files.readString(dir.resolve("oauth-client-gateway-web.secret")).strip()).isEqualTo(first);
    }

    @Test
    void 외부_값도_개발용_디렉터리도_없으면_기동_실패() {
        assertThatThrownBy(() -> ClientSecretSource.resolve("gateway-admin", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GATEWAY_ADMIN_CLIENT_SECRET");
    }

    @Test
    void 없으면_해시로_등록하고_같은_설정으로_다시_돌면_바꾸지_않는다() {
        RegisteredClientSync sync = sync(config("http://localhost:5173/login/oauth2/code/gateway-web"));

        sync.sync("gateway-web", config("http://localhost:5173/login/oauth2/code/gateway-web"), "secret-1");
        RegisteredClient saved = repository.findByClientId("gateway-web");
        assertThat(saved.getId()).isEqualTo("gateway-web");
        assertThat(saved.getClientSecret()).startsWith("{bcrypt}").doesNotContain("secret-1");
        assertThat(encoder.matches("secret-1", saved.getClientSecret())).isTrue();
        assertThat(saved.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(saved.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(saved.getTokenSettings().isReuseRefreshTokens()).isFalse();

        sync.sync("gateway-web", config("http://localhost:5173/login/oauth2/code/gateway-web"), "secret-1");
        assertThat(repository.saves).isEqualTo(1);
        assertThat(repository.findByClientId("gateway-web").getClientSecret()).isEqualTo(saved.getClientSecret());
    }

    @Test
    void 시크릿이_바뀌면_해시를_바꾸고_redirect_URI_가_바뀌면_갱신한다() {
        RegisteredClientSync sync = sync(config("http://localhost:5173/cb"));
        sync.sync("gateway-web", config("http://localhost:5173/cb"), "secret-1");

        sync.sync("gateway-web", config("http://localhost:5173/cb"), "secret-2");
        RegisteredClient rotated = repository.findByClientId("gateway-web");
        assertThat(encoder.matches("secret-2", rotated.getClientSecret())).isTrue();
        assertThat(encoder.matches("secret-1", rotated.getClientSecret())).isFalse();

        sync.sync("gateway-web", config("https://example.com/login/oauth2/code/gateway-web"), "secret-2");
        RegisteredClient moved = repository.findByClientId("gateway-web");
        assertThat(moved.getRedirectUris()).containsExactly("https://example.com/login/oauth2/code/gateway-web");
        assertThat(moved.getClientSecret()).isEqualTo(rotated.getClientSecret());
        assertThat(moved.getClientIdIssuedAt()).isEqualTo(rotated.getClientIdIssuedAt());
        assertThat(repository.saves).isEqualTo(3);
    }

    @Test
    void redirect_URI_가_없으면_기동_실패() {
        RegisteredClientSync sync = sync(config());
        assertThatThrownBy(() -> sync.sync("gateway-web", config(), "secret-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    private RegisteredClientSync sync(OAuthClientProperties.Client client) {
        OAuthClientProperties properties = new OAuthClientProperties(Map.of("gateway-web", client), dir.toString(),
                Duration.ofMinutes(15), Duration.ofDays(14));
        return new RegisteredClientSync(repository, encoder, properties, Clock.systemUTC());
    }

    private static OAuthClientProperties.Client config(String... redirectUris) {
        return new OAuthClientProperties.Client(null, List.of(redirectUris), List.of("http://localhost:5173/"));
    }

    private static final class InMemoryClients implements RegisteredClientRepository {

        private final Map<String, RegisteredClient> byId = new HashMap<>();
        private int saves;

        @Override
        public void save(RegisteredClient client) {
            byId.put(client.getId(), client);
            saves++;
        }

        @Override
        public RegisteredClient findById(String id) {
            return byId.get(id);
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return byId.values().stream().filter(c -> c.getClientId().equals(clientId)).findFirst().orElse(null);
        }
    }
}
