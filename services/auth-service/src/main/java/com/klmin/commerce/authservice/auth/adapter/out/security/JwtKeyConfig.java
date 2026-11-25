package com.klmin.commerce.authservice.auth.adapter.out.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 서명 키를 읽어 두 가지를 만든다.
 * <ul>
 *   <li>{@link JwtKeys}: 서명용 현재 키 + JWKS 로 내보낼 공개키(현재 + 은퇴한 키)</li>
 *   <li>{@link JwtDecoder}: auth-service 자신의 API(로그인 기기 관리, 전체 로그아웃)가 access token 을 검증할 때.
 *       JWKS 와 같은 공개키 집합과 issuer 로 검증한다</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    JwtKeys jwtKeys(JwtProperties properties, ResourceLoader resourceLoader) {
        RSAKey signingKey = signingKey(properties, resourceLoader);
        List<JWK> publicKeys = new ArrayList<>();
        publicKeys.add(signingKey.toPublicJWK());
        for (JwtProperties.RetiredKey retired : properties.retiredKeys()) {
            RSAPublicKey key = readPublicKey(read(resourceLoader, retired.publicKeyLocation()));
            publicKeys.add(new RSAKey.Builder(key).keyID(retired.keyId())
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build());
        }
        return new JwtKeys(properties.issuer(), signingKey, new JWKSet(publicKeys));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(keys.publicKeys())));
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(keys.issuer()));
        return decoder;
    }

    private static RSAKey signingKey(JwtProperties properties, ResourceLoader resourceLoader) {
        // 1. 주입받은 키 (k8s·운영)  2. 로컬 개발용 키 파일 (있으면 쓰고 없으면 만든다)  3. 기동할 때마다 새 임시 키
        if (properties.privateKeyLocation() == null || properties.privateKeyLocation().isBlank()) {
            if (properties.devKeyFile() != null && !properties.devKeyFile().isBlank()) {
                return DevSigningKeyFile.loadOrCreate(Path.of(properties.devKeyFile()));
            }
            String keyId = "dev-" + UUID.randomUUID().toString().substring(0, 8);
            log.warn("auth.jwt.private-key-location·dev-key-file 이 비어 임시 서명 키(kid={})를 만든다. 재시작하면 이전 토큰은 검증되지 않는다", keyId);
            return generate(keyId);
        }
        if (properties.keyId() == null || properties.keyId().isBlank()) {
            throw new IllegalStateException("auth.jwt.key-id 가 필요하다 (서명 키를 지정했을 때)");
        }
        RSAPrivateCrtKey privateKey = readPrivateKey(read(resourceLoader, properties.privateKeyLocation()));
        try {
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(properties.keyId())
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("서명 키에서 공개키를 만들 수 없다", e);
        }
    }

    private static RSAKey generate(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(ResourceLoader resourceLoader, String location) {
        try {
            return resourceLoader.getResource(location).getContentAsString(StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException("키 파일을 읽을 수 없다: " + location, e);
        }
    }

    static RSAPrivateCrtKey readPrivateKey(String pem) {
        try {
            return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der(pem, "PRIVATE KEY")));
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("RSA PKCS#8 개인키(PEM)가 아니다", e);
        }
    }

    static RSAPublicKey readPublicKey(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der(pem, "PUBLIC KEY")));
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("RSA 공개키(X.509 PEM)가 아니다", e);
        }
    }

    private static byte[] der(String pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int from = pem.indexOf(begin);
        int to = pem.indexOf(end);
        if (from < 0 || to < from) {
            throw new IllegalStateException(type + " PEM 이 아니다");
        }
        return Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
    }
}
