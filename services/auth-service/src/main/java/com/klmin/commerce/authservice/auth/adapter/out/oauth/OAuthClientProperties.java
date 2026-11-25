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
 * @param refreshTokenTtl   refresh token 수명. 갱신할 때마다 새 refresh token(회전)이라 쓰는 동안 연장된다
 */
@ConfigurationProperties("auth.oauth")
record OAuthClientProperties(Map<String, Client> clients, String secretDevDir, Duration accessTokenTtl,
                             Duration refreshTokenTtl) {

    OAuthClientProperties {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
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
