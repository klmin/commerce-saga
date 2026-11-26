package com.klmin.commerce.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * 클라이언트 시크릿을 찾는 순서 (설계 문서 6절). auth-service 의 같은 이름 클래스와 규칙이 같다
 * (서비스끼리 모듈을 공유하지 않아 따로 둔다).
 * <ol>
 *   <li>외부에서 준 값({@code GATEWAY_WEB_CLIENT_SECRET} 등)</li>
 *   <li>로컬 개발용 파일 {@code <dir>/oauth-client-<id>.secret}: 있으면 읽고 없으면 만든다(auth-service 와 같은 파일)</li>
 *   <li>둘 다 없으면 기동 실패(조용히 만들면 auth-service 와 값이 어긋나 {@code invalid_client})</li>
 * </ol>
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
            throw new IllegalStateException("클라이언트 " + clientId + " 의 시크릿이 없다. 외부 변수("
                    + clientId.replace('-', '_').toUpperCase(Locale.ROOT) + "_CLIENT_SECRET)로 주거나, 로컬 개발이면 "
                    + "gateway.bff.secret-dev-dir 를 둔다");
        }
        return loadOrCreate(Path.of(devDir).resolve("oauth-client-" + clientId + ".secret"));
    }

    private static String loadOrCreate(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                try {
                    Files.writeString(file, generate(), StandardCharsets.US_ASCII,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                    }
                    log.warn("로컬 개발용 클라이언트 시크릿을 새로 만들었다: {}", file);
                } catch (FileAlreadyExistsException e) {
                    // auth-service 가 먼저 만들었다. 아래에서 읽는다
                }
            }
            for (int attempt = 0; attempt < 20; attempt++) {
                String value = Files.readString(file, StandardCharsets.US_ASCII).strip();
                if (!value.isEmpty()) {
                    return value;
                }
                Thread.sleep(50);
            }
            throw new IllegalStateException("로컬 개발용 클라이언트 시크릿 파일이 비어 있다: " + file);
        } catch (IOException e) {
            throw new UncheckedIOException("로컬 개발용 클라이언트 시크릿 파일을 읽거나 만들 수 없다: " + file, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
