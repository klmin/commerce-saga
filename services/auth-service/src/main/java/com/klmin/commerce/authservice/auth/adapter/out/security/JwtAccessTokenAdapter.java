package com.klmin.commerce.authservice.auth.adapter.out.security;

import com.klmin.commerce.authservice.auth.application.port.out.AccessTokenPort;
import com.klmin.commerce.authservice.auth.application.port.out.AccessTokenResult;
import com.klmin.commerce.authservice.auth.domain.Role;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * access token = JWT RS256 (ADR 0008). 클레임은 {@code iss}, {@code sub}(userId), {@code roles}, {@code iat}, {@code exp}.
 * 헤더의 {@code kid} 로 검증하는 쪽(Gateway·서비스)이 JWKS 에서 공개키를 고른다.
 */
@Component
class JwtAccessTokenAdapter implements AccessTokenPort {

    private final JwtKeys keys;
    private final JwtEncoder encoder;

    JwtAccessTokenAdapter(JwtKeys keys) {
        this.keys = keys;
        // 서명에는 현재 키 하나만 쓴다
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    }

    @Override
    public AccessTokenResult issue(long userId, Set<Role> roles, Instant now, Duration ttl) {
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(keys.issuer())
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("roles", roles.stream().map(Role::name).sorted().toList())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.signingKey().getKeyID()).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessTokenResult(value, expiresAt);
    }

    @Override
    public Map<String, Object> publicJwks() {
        return keys.publicKeys().toJSONObject(true);
    }
}
