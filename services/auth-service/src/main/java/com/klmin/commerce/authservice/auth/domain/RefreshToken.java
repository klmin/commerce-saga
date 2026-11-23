package com.klmin.commerce.authservice.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * refresh token = {@code familyId.secret} (JWT 아님, ADR 0008).
 * 서버는 secret 원문을 두지 않고 SHA-256 해시만 둔다(split token). familyId 만 알아서는 아무것도 할 수 없다.
 */
public record RefreshToken(String familyId, String secret) {

    // familyId: UUID 문자열, secret: 32바이트 base64url (패딩 없음, 43자)
    private static final Pattern FAMILY_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");

    public RefreshToken {
        if (familyId == null || !FAMILY_ID.matcher(familyId).matches()
                || secret == null || !SECRET.matcher(secret).matches()) {
            throw new IllegalArgumentException("refresh token 형식이 아니다");
        }
    }

    /** 쿠키 값에서 읽는다. 형식이 틀리면 empty (원인을 구분해 알려 주지 않는다) */
    public static Optional<RefreshToken> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int dot = raw.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RefreshToken(raw.substring(0, dot), raw.substring(dot + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String value() {
        return familyId + "." + secret;
    }

    public String secretHash() {
        return hash(secret);
    }

    static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "RefreshToken[familyId=" + familyId + ", secret=***]";
    }
}
