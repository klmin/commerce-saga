package com.klmin.commerce.authservice.auth.adapter.out.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * access token 서명 키 (ADR 0008). application.yml 의 {@code auth.jwt}.
 *
 * @param keyId              서명 키의 kid. JWT 헤더와 JWKS 에 들어가 검증하는 쪽이 키를 고른다
 * @param privateKeyLocation 주입받는 서명 키 (RSA, PKCS#8 PEM, 예: {@code file:/etc/auth-jwt/private.pem}). k8s·운영
 * @param devKeyFile         privateKeyLocation 이 비었을 때 쓰는 로컬 개발용 키 파일 경로. 있으면 쓰고 없으면 만들어 저장한다.
 *                           이것도 비우면 기동할 때마다 임시 키를 만든다
 * @param retiredKeys        키를 바꾼 뒤에도 이전 키로 서명된 토큰이 끝날 때까지 JWKS 에 함께 내보낼 공개키
 */
@ConfigurationProperties("auth.jwt")
record JwtProperties(String issuer, String keyId, String privateKeyLocation, String devKeyFile,
                     List<RetiredKey> retiredKeys) {

    JwtProperties {
        retiredKeys = retiredKeys == null ? List.of() : List.copyOf(retiredKeys);
    }

    /** @param publicKeyLocation RSA 공개키 (X.509 PEM) */
    record RetiredKey(String keyId, String publicKeyLocation) {
    }
}
