package com.klmin.commerce.authservice.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 로그인 한 번(기기 하나)에서 이어지는 refresh token 들의 묶음 (ADR 0008, refresh token rotation).
 * refresh 할 때마다 secret 이 바뀌고, 이미 바뀐 옛 secret 이 다시 오면 탈취로 보고 family 를 통째로 버린다.
 *
 * @param currentHash        지금 유효한 secret 의 해시
 * @param previousHash       직전 secret 의 해시. {@code previousValidUntil} 전까지만 동시 refresh 로 봐준다
 * @param expiresAt          쓰지 않으면 끝나는 시각. refresh 마다 연장한다 (sliding)
 * @param absoluteExpiresAt  처음 로그인부터의 절대 만료. 연장해도 넘지 않는다
 */
public record RefreshFamily(String familyId, long userId, String agent, String currentHash,
                            String previousHash, Instant previousValidUntil,
                            Instant createdAt, Instant lastUsedAt, Instant expiresAt, Instant absoluteExpiresAt) {

    public RefreshFamily {
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(currentHash, "currentHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        agent = agent == null ? "" : agent;
    }

    /** 로그인할 때 새 family */
    public static RefreshFamily start(RefreshToken token, long userId, String agent, Instant now,
                                      Duration ttl, Duration absoluteTtl) {
        Instant absolute = now.plus(absoluteTtl);
        return new RefreshFamily(token.familyId(), userId, agent, token.secretHash(), null, null,
                now, now, min(now.plus(ttl), absolute), absolute);
    }

    public RefreshDecision decide(RefreshToken presented, Instant now) {
        if (!now.isBefore(expiresAt) || !now.isBefore(absoluteExpiresAt)) {
            return RefreshDecision.EXPIRED;
        }
        String hash = presented.secretHash();
        if (sameHash(hash, currentHash)) {
            return RefreshDecision.ROTATE;
        }
        if (previousHash != null && previousValidUntil != null && now.isBefore(previousValidUntil)
                && sameHash(hash, previousHash)) {
            return RefreshDecision.GRACE;
        }
        return RefreshDecision.REUSE_DETECTED;
    }

    /** 새 secret 으로 바꾼 family. 지금 secret 은 {@code grace} 동안만 직전 secret 으로 남는다 */
    public RefreshFamily rotate(RefreshToken next, Instant now, Duration ttl, Duration grace) {
        if (!next.familyId().equals(familyId)) {
            throw new IllegalArgumentException("다른 family 의 토큰으로 회전할 수 없다");
        }
        return new RefreshFamily(familyId, userId, agent, next.secretHash(), currentHash, now.plus(grace),
                createdAt, now, min(now.plus(ttl), absoluteExpiresAt), absoluteExpiresAt);
    }

    public Duration remainingLifetime(Instant now) {
        Duration remaining = Duration.between(now, expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    // 상수 시간 비교 (어디까지 맞았는지 응답 시간으로 드러나지 않게)
    private static boolean sameHash(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
