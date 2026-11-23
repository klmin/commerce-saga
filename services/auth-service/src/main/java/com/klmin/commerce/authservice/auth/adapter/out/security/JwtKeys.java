package com.klmin.commerce.authservice.auth.adapter.out.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * @param signingKey 서명용 현재 키 (개인키 포함, 밖으로 내보내지 않는다)
 * @param publicKeys 검증용 공개키 전부 (현재 + 은퇴한 키). JWKS 로 내보낸다
 */
record JwtKeys(String issuer, RSAKey signingKey, JWKSet publicKeys) {

    @Override
    public String toString() {
        return "JwtKeys[issuer=" + issuer + ", kid=" + signingKey.getKeyID() + "]";
    }
}
