package com.klmin.commerce.authservice.auth.adapter.out.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 클라이언트 시크릿을 찾는 순서 (설계 문서 6절, Gateway 도 같은 규칙):
 * <ol>
 *   <li>외부에서 준 값({@code GATEWAY_WEB_CLIENT_SECRET} 등). compose 는 init 컨테이너가 만든 값, k8s 는 Secret</li>
 *   <li>로컬 개발용 파일 {@code <dir>/oauth-client-<id>.secret}: 있으면 읽고 없으면 만든다. 호스트에서 띄우는 auth-service 와
 *       Gateway 가 같은 파일을 읽어 값이 맞는다. 저장소에 값이 남지 않는다</li>
 *   <li>둘 다 없으면 기동 실패. 조용히 만들면 Gateway 와 값이 어긋나 로그인 때 {@code invalid_client} 로 터진다</li>
 * </ol>
 * 앱이 시크릿을 만들어 DB 에만 두지 않는다(DB 에는 해시뿐이라 Gateway 가 원문을 알 수 없다).
 */
final class ClientSecretSource {

    private static final Logger log = LoggerFactory.getLogger(ClientSecretSource.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private ClientSecretSource() {
    }

    static String resolve(String clientId, String configured, String devDir) {
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        if (devDir == null || devDir.isBlank()) {
            throw new IllegalStateException("클라이언트 " + clientId + " 의 시크릿이 없다. 외부 변수(예: "
                    + envName(clientId) + ")로 주거나, 로컬 개발이면 auth.oauth.secret-dev-dir 를 둔다");
        }
        return loadOrCreate(Path.of(devDir).resolve("oauth-client-" + clientId + ".secret"));
    }

    static String envName(String clientId) {
        return clientId.replace('-', '_').toUpperCase(java.util.Locale.ROOT) + "_CLIENT_SECRET";
    }

    // 여러 프로세스(auth-service, Gateway)가 동시에 처음 떠도 CREATE_NEW 로 하나만 만들고 나머지는 그 값을 읽는다
    private static String loadOrCreate(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                try {
                    Files.writeString(file, generate(), StandardCharsets.US_ASCII,
                            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
                    ownerOnly(file);
                    log.warn("로컬 개발용 클라이언트 시크릿을 새로 만들었다: {}", file);
                } catch (FileAlreadyExistsException e) {
                    // 다른 프로세스가 먼저 만들었다. 아래에서 읽는다
                }
            }
            String secret = readComplete(file);
            log.info("로컬 개발용 클라이언트 시크릿 파일을 쓴다: {}", file);
            return secret;
        } catch (IOException e) {
            throw new UncheckedIOException("로컬 개발용 클라이언트 시크릿 파일을 읽거나 만들 수 없다: " + file, e);
        }
    }

    // 상대가 막 만들어 아직 쓰는 중일 수 있어 빈 값이면 잠깐 기다려 다시 읽는다
    private static String readComplete(Path file) throws IOException {
        for (int attempt = 0; attempt < 20; attempt++) {
            String value = Files.readString(file, StandardCharsets.US_ASCII).strip();
            if (!value.isEmpty()) {
                return value;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("로컬 개발용 클라이언트 시크릿 파일이 비어 있다. 지우면 다음 기동 때 새로 만든다: " + file);
    }

    private static void ownerOnly(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
