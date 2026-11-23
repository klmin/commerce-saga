package com.klmin.commerce.authservice.auth.adapter.out.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * access token 서명 키 (ADR 0008). application.yml 의 {@code auth.jwt}.
 *
 * @param keyId              서명 키의 kid. JWT 헤더와 JWKS 에 들어가 검증하는 쪽이 키를 고른다
 * @param privateKeyLocation 서명 키 (RSA, PKCS#8 PEM). 비우면 기동할 때 임시 키를 만든다(로컬 개발용)
 * @param retiredKeys        키를 바꾼 뒤에도 이전 키로 서명된 토큰이 끝날 때까지 JWKS 에 함께 내보낼 공개키
 */
@ConfigurationProperties("auth.jwt")
record JwtProperties(String issuer, String keyId, String privateKeyLocation, List<RetiredKey> retiredKeys) {

    JwtProperties {
        retiredKeys = retiredKeys == null ? List.of() : List.copyOf(retiredKeys);
    }

    /** @param publicKeyLocation RSA 공개키 (X.509 PEM) */
    record RetiredKey(String keyId, String publicKeyLocation) {
    }
}
