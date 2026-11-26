package com.klmin.commerce.authservice.auth.adapter.out.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 고정 등록하는 OAuth 클라이언트 (ADR 0017, 설계 문서 6절). application.yml 의 {@code auth.oauth}.
 *
 * @param clients           client-id → 설정. Gateway(BFF) 의 {@code gateway-web}, {@code gateway-admin}
 * @param secretDevDir      시크릿을 외부에서 주지 않았을 때 쓰는 로컬 개발용 파일 디렉터리
 *                          ({@code oauth-client-<id>.secret}). 비우면(compose·k8s) 시크릿이 없을 때 기동 실패
 * @param accessTokenTtl    access token 수명 (ADR 0008 과 같은 15분)
 * @param refreshTokenTtl   refresh token 수명 (로그인부터, 회전하지 않는다)
 * @param scriptsClient     시나리오 스크립트(curl)가 사용자 토큰을 받는 CLI 클라이언트
 */
@ConfigurationProperties("auth.oauth")
record OAuthClientProperties(Map<String, Client> clients, String secretDevDir, Duration accessTokenTtl,
                             Duration refreshTokenTtl, ScriptsClient scriptsClient) {

    OAuthClientProperties {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
        scriptsClient = scriptsClient == null ? new ScriptsClient(false, null) : scriptsClient;
    }

    /**
     * 시나리오 스크립트용 공개 클라이언트 {@value #ID} (후속 5-3c). 시크릿 없이 PKCE 로 인가 코드 흐름을 돌아 사용자 토큰을 받는다
     * (네이티브 앱 방식, RFC 8252). redirect URI 는 루프백이고 실제로 받지 않는다: 스크립트가 리다이렉트 주소에서 코드를 읽는다.
     * 사용자의 아이디·비밀번호가 있어야 하므로 개발용 시드 사용자가 없는 환경(EKS)에서는 끈다
     *
     * @param enabled     등록할지
     * @param redirectUri 루프백 콜백 (정확히 일치)
     */
    record ScriptsClient(boolean enabled, String redirectUri) {
        static final String ID = "scripts";
    }

    /**
     * @param secret                 외부에서 주는 시크릿(최우선). 비면 {@code secretDevDir} 의 파일
     * @param redirectUris           인가 코드 콜백. 등록된 정확한 값만 허용된다
     * @param postLogoutRedirectUris OIDC 로그아웃 뒤 돌아갈 주소(절대 주소, 정확히 일치)
     */
    record Client(String secret, List<String> redirectUris, List<String> postLogoutRedirectUris) {

        Client {
            redirectUris = clean(redirectUris);
            postLogoutRedirectUris = clean(postLogoutRedirectUris);
        }

        @Override
        public String toString() {
            return "Client[secret=***, redirectUris=" + redirectUris + ", postLogoutRedirectUris="
                    + postLogoutRedirectUris + "]";
        }

        private static List<String> clean(List<String> values) {
            return values == null ? List.of() : values.stream().map(String::strip).filter(v -> !v.isEmpty()).toList();
        }
    }
}
