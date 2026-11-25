package com.klmin.commerce.authservice.auth.adapter.out.security;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서명 키를 고르는 순서: 주입받은 키 → 로컬 개발용 키 파일(있으면 쓰고 없으면 만든다) → 임시 키
 */
class JwtKeyConfigTest {

    @TempDir
    Path dir;

    private final JwtKeyConfig config = new JwtKeyConfig();
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

    @Test
    void 로컬_키_파일이_없으면_만들고_다음_기동에도_같은_키를_쓴다() {
        Path file = dir.resolve("nested/auth-jwt-dev.pem");

        RSAKey first = signingKey(properties(null, null, file.toString()));
        RSAKey second = signingKey(properties(null, null, file.toString()));

        assertThat(file).exists();
        assertThat(first.getKeyID()).startsWith("dev-").isEqualTo(second.getKeyID());
        assertThat(first.getModulus()).isEqualTo(second.getModulus());
        // 만든 파일은 주입용 키와 같은 형식(PKCS#8 PEM)이다
        assertThat(JwtKeyConfig.readPrivateKey(read(file)).getModulus()).isEqualTo(first.getModulus().decodeToBigInteger());
    }

    @Test
    void 로컬_키_파일이_있으면_그_키를_쓴다() throws Exception {
        Path file = dir.resolve("existing.pem");
        RSAPrivateCrtKey existing = newKey();
        Files.writeString(file, DevSigningKeyFile.pem(existing));

        RSAKey key = signingKey(properties(null, null, file.toString()));

        assertThat(key.getModulus().decodeToBigInteger()).isEqualTo(existing.getModulus());
    }

    @Test
    void 로컬_키_파일이_깨졌으면_경로를_알려_주며_기동을_멈춘다() throws Exception {
        Path file = dir.resolve("broken.pem");
        Files.writeString(file, "not a key");

        assertThatThrownBy(() -> signingKey(properties(null, null, file.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void 처음_동시에_떠도_키는_하나만_남는다() throws Exception {
        Path file = dir.resolve("race.pem");
        int n = 8;
        CountDownLatch start = new CountDownLatch(1);
        Callable<RSAKey> task = () -> {
            start.await();
            return signingKey(properties(null, null, file.toString()));
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<RSAKey>> futures = IntStream.range(0, n).mapToObj(i -> pool.submit(task)).toList();
            start.countDown();
            String kid = futures.getFirst().get().getKeyID();
            for (Future<RSAKey> f : futures) {
                assertThat(f.get().getKeyID()).isEqualTo(kid);
            }
        }
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("race.pem");
        }
    }

    @Test
    void 주입받은_키가_있으면_로컬_키_파일보다_먼저_쓰고_파일을_만들지_않는다() throws Exception {
        Path injected = dir.resolve("injected.pem");
        RSAPrivateCrtKey key = newKey();
        Files.writeString(injected, DevSigningKeyFile.pem(key));
        Path devFile = dir.resolve("dev.pem");

        RSAKey signing = signingKey(properties(injected.toUri().toString(), "k8s-1", devFile.toString()));

        assertThat(signing.getKeyID()).isEqualTo("k8s-1");
        assertThat(signing.getModulus().decodeToBigInteger()).isEqualTo(key.getModulus());
        assertThat(devFile).doesNotExist();
    }

    @Test
    void 로컬_키_파일을_만들_수_없으면_기동을_막지_않고_임시_키를_쓴다() throws Exception {
        // 홈에 쓸 수 없는 컨테이너처럼: 상위 경로가 디렉터리가 아니라 파일이라 디렉터리를 만들 수 없다
        Path notADirectory = Files.writeString(dir.resolve("home"), "file");
        Path file = notADirectory.resolve(".commerce-saga/auth-jwt-dev.pem");

        RSAKey first = signingKey(properties(null, null, file.toString()));
        RSAKey second = signingKey(properties(null, null, file.toString()));

        assertThat(file).doesNotExist();
        assertThat(first.getKeyID()).isNotEqualTo(second.getKeyID());
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("home");
        }
    }

    @Test
    void 둘_다_비면_기동할_때마다_임시_키를_만든다() {
        RSAKey first = signingKey(properties(null, null, ""));
        RSAKey second = signingKey(properties(null, null, null));

        assertThat(first.getKeyID()).isNotEqualTo(second.getKeyID());
        assertThat(first.getModulus()).isNotEqualTo(second.getModulus());
    }

    private RSAKey signingKey(JwtProperties properties) {
        JwtKeys keys = config.jwtKeys(properties, resourceLoader);
        // JWKS 에는 서명 키의 공개키가 같은 kid 로 나간다
        assertThat(keys.publicKeys().getKeyByKeyId(keys.signingKey().getKeyID())).isNotNull();
        return keys.signingKey();
    }

    private static JwtProperties properties(String privateKeyLocation, String keyId, String devKeyFile) {
        return new JwtProperties("test-issuer", keyId, privateKeyLocation, devKeyFile, null);
    }

    private static RSAPrivateCrtKey newKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return (RSAPrivateCrtKey) generator.generateKeyPair().getPrivate();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
