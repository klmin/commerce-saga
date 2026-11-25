package com.klmin.commerce.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Gateway 를 BFF(OAuth2 클라이언트)로 쓸 때의 설정 (ADR 0017, 설계 문서 6·7절). application.yml 의 {@code gateway.bff}.
 *
 * @param issuer             SAS 공개 주소({@code AUTH_ISSUER}). 브라우저가 가는 인가·로그아웃 주소와 ID 토큰 {@code iss} 검사
 * @param internalUrl        SAS 내부 주소({@code AUTH_INTERNAL_URL}). 토큰 교환·JWKS (컨테이너 안에서 공개 주소가 자기 자신을 가리키지 않게)
 * @param adminClientHost    이 호스트(포트 포함, 예 {@code admin.example.com}, {@code localhost:5174})로 들어온 로그인은
 *                           {@code gateway-admin} 클라이언트. 비우면(경로 방식) 늘 {@code gateway-web}
 * @param webHome            로그인·로그아웃 뒤 기본으로 갈 사용자 화면. 절대 주소 또는 경로({@code /})
 * @param adminHome          관리자 화면. 절대 주소 또는 경로({@code /admin/})
 * @param web                {@code gateway-web} 클라이언트
 * @param admin              {@code gateway-admin} 클라이언트
 * @param secretDevDir       시크릿을 외부에서 주지 않았을 때 읽는 로컬 개발용 파일 디렉터리(auth-service 와 같은 파일)
 */
@ConfigurationProperties("gateway.bff")
record BffProperties(String issuer, String internalUrl, String adminClientHost, String webHome, String adminHome,
                     Client web, Client admin, String secretDevDir) {

    static final String WEB = "gateway-web";
    static final String ADMIN = "gateway-admin";

    BffProperties {
        if (issuer == null || issuer.isBlank() || issuer.endsWith("/")) {
            throw new IllegalStateException("gateway.bff.issuer(AUTH_ISSUER) 가 필요하고 / 로 끝나면 안 된다: " + issuer);
        }
        adminClientHost = adminClientHost == null ? "" : adminClientHost.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * @param secret      외부에서 주는 시크릿({@code GATEWAY_WEB_CLIENT_SECRET} 등). 비면 개발용 파일
     * @param redirectUri 인가 코드 콜백. auth-service 에 등록된 값과 정확히 같아야 한다
     */
    record Client(String secret, String redirectUri) {

        @Override
        public String toString() {
            return "Client[secret=***, redirectUri=" + redirectUri + "]";
        }
    }
}
