package com.klmin.commerce.authservice.auth.adapter.out.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * 로컬 개발용 서명 키 파일 (ADR 0008). 키를 주입받지 않았을 때만 쓴다.
 * 파일이 있으면 그 키를, 없으면 새로 만들어 저장한 뒤 쓴다. 그래서 auth-service 를 다시 띄워도 이전 토큰이 검증된다.
 *
 * <p>k8s·운영은 키를 주입받으므로({@code auth.jwt.private-key-location}) 여기까지 오지 않는다.
 * 앱이 키를 만들어 두는 건 쓰기 가능한 로컬 디스크와 인스턴스 하나를 전제로 한 개발 편의다.
 */
final class DevSigningKeyFile {

    private static final Logger log = LoggerFactory.getLogger(DevSigningKeyFile.class);

    private DevSigningKeyFile() {
    }

    /**
     * @return 파일의 키. 파일이 없고 만들 수도 없으면(쓰기 금지 디스크·홈이 없는 컨테이너 등) 비어 있다 → 호출한 쪽이 임시 키
     * @throws IllegalStateException 파일은 있는데 키가 아니면 (지우면 다시 만든다)
     */
    static Optional<RSAKey> loadOrCreate(Path file) {
        if (!Files.exists(file) && !create(file)) {
            return Optional.empty();
        }
        String pem;
        try {
            pem = Files.readString(file, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException("로컬 개발용 서명 키 파일을 읽을 수 없다: " + file, e);
        }
        RSAPrivateCrtKey privateKey;
        try {
            privateKey = JwtKeyConfig.readPrivateKey(pem);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("로컬 개발용 서명 키 파일이 RSA PKCS#8 PEM 이 아니다. 지우면 다음 기동 때 새로 만든다: " + file, e);
        }
        RSAKey key = toJwk(privateKey);
        log.info("로컬 개발용 서명 키 파일을 쓴다: {} (kid={})", file, key.getKeyID());
        return Optional.of(key);
    }

    /**
     * 임시 파일에 다 쓴 뒤 최종 이름으로 하드 링크를 건다. 링크는 대상이 있으면 OS 가 원자적으로 거절한다(POSIX link, Windows
     * CreateHardLink). 그래서 여러 프로세스가 동시에 처음 떠도 먼저 건 쪽의 키 하나만 남고, 반쯤 쓴 파일을 읽지 않는다.
     * ({@code Files.move} 는 Linux 에서 대상 확인과 rename 사이에 경쟁이 있어 덮어쓸 수 있다)
     *
     * @return 파일이 생겼으면(내가 만들었든 다른 프로세스가 먼저 만들었든) true, 만들 수 없으면 false
     */
    private static boolean create(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
            ownerOnly(temp);
            Files.writeString(temp, pem(generate()), StandardCharsets.US_ASCII);
            publish(temp, file);
            log.warn("로컬 개발용 서명 키를 새로 만들었다: {}. 이전 토큰은 검증되지 않는다(refresh 로 다시 받는다)", file);
            return true;
        } catch (FileAlreadyExistsException e) {
            // 다른 인스턴스가 먼저 만들었다. 그 키를 쓴다
            return true;
        } catch (IOException | UncheckedIOException e) {
            log.warn("로컬 개발용 서명 키 파일을 만들 수 없어 임시 키를 쓴다(재시작하면 이전 토큰 무효): {} ({})", file, e.toString());
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 임시 파일 정리 실패는 기동을 막지 않는다
                }
            }
        }
    }

    private static void publish(Path temp, Path file) throws IOException {
        try {
            Files.createLink(file, temp);
        } catch (UnsupportedOperationException e) {
            // 하드 링크가 없는 파일 시스템: 대상이 없을 때만 옮긴다 (동시 첫 기동의 배타성은 보장하지 못한다)
            Files.move(temp, file);
        }
    }

    private static void ownerOnly(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static RSAPrivateCrtKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return (RSAPrivateCrtKey) generator.generateKeyPair().getPrivate();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String pem(RSAPrivateCrtKey key) {
        // getEncoded() 는 PKCS#8 DER
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    /** kid 는 공개키 지문(RFC 7638)이라 따로 저장하지 않아도 같은 파일이면 같은 kid 다 */
    private static RSAKey toJwk(RSAPrivateCrtKey privateKey) {
        try {
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            RSAKey withoutKid = new RSAKey.Builder(publicKey).build();
            String kid = "dev-" + withoutKid.computeThumbprint().toString().substring(0, 16);
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(kid)
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        } catch (GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException("서명 키에서 공개키를 만들 수 없다", e);
        }
    }
}
