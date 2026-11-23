package com.klmin.commerce.authservice.auth.adapter.out.redis;

import com.klmin.commerce.authservice.auth.application.port.out.RefreshFamilyPort;
import com.klmin.commerce.authservice.auth.domain.RefreshFamily;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * refresh token family 를 Redis 에 둔다 (ADR 0008).
 * <ul>
 *   <li>{@code auth:family:<familyId>} (hash): 사용자, 기기, 현재·직전 secret 해시, 시각들. {@code expiresAt} 에 저절로 지워진다</li>
 *   <li>{@code auth:user:<userId>:families} (sorted set): 사용자의 familyId 목록, 점수 = 그 family 의 만료 시각(ms).
 *       기기 목록·전체 로그아웃에 쓴다. 끝난 항목은 조회할 때 {@code ZREMRANGEBYSCORE} 로 정리한다</li>
 * </ul>
 * family 와 목록을 함께 바꾸는 일은 Lua 스크립트 하나로 해 원자적이다. 회전은 "현재 secret 해시가 아직 그대로면" 바꾸는
 * compare-and-set 이라, 같은 토큰으로 동시에 refresh 해도 한 번만 회전한다.
 * 키 이름을 스크립트 안에서 만드는 곳(deleteAll)이 있어 Redis Cluster 에서는 hash tag 로 바꿔야 한다 (지금은 단일 노드).
 */
@Component
class RedisRefreshFamilyAdapter implements RefreshFamilyPort {

    // 목록 키의 만료는 가장 늦게 끝나는 family 에 맞춘다
    private static final String EXTEND_INDEX = """
            local last = redis.call('zrange', KEYS[2], -1, -1, 'WITHSCORES')
            if last[2] then redis.call('pexpireat', KEYS[2], last[2]) end
            """;

    // KEYS: family, index / ARGV: expiresAtMs, familyId, field1, value1, ...
    private static final RedisScript<Long> CREATE = RedisScript.of("""
            redis.call('hset', KEYS[1], unpack(ARGV, 3))
            redis.call('pexpireat', KEYS[1], ARGV[1])
            redis.call('zadd', KEYS[2], ARGV[1], ARGV[2])
            """ + EXTEND_INDEX + """
            return 1
            """, Long.class);

    // KEYS: family, index / ARGV: expectedCurrentHash, expiresAtMs, familyId, field1, value1, ...
    private static final RedisScript<Long> REPLACE = RedisScript.of("""
            if redis.call('hget', KEYS[1], 'current') ~= ARGV[1] then return 0 end
            redis.call('hset', KEYS[1], unpack(ARGV, 4))
            redis.call('pexpireat', KEYS[1], ARGV[2])
            redis.call('zadd', KEYS[2], ARGV[2], ARGV[3])
            """ + EXTEND_INDEX + """
            return 1
            """, Long.class);

    // KEYS: family, index / ARGV: familyId
    private static final RedisScript<Long> DELETE = RedisScript.of("""
            redis.call('del', KEYS[1])
            redis.call('zrem', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    // KEYS: index / ARGV: family key prefix
    private static final RedisScript<Long> DELETE_ALL = RedisScript.of("""
            local ids = redis.call('zrange', KEYS[1], 0, -1)
            for _, id in ipairs(ids) do redis.call('del', ARGV[1] .. id) end
            redis.call('del', KEYS[1])
            return #ids
            """, Long.class);

    private static final String FAMILY_PREFIX = "auth:family:";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RedisRefreshFamilyAdapter.class);

    private final StringRedisTemplate redis;

    RedisRefreshFamilyAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void create(RefreshFamily family) {
        List<String> args = new ArrayList<>();
        args.add(millis(family.expiresAt()));
        args.add(family.familyId());
        args.addAll(fields(family));
        redis.execute(CREATE, List.of(familyKey(family.familyId()), indexKey(family.userId())), args.toArray());
    }

    @Override
    public Optional<RefreshFamily> find(String familyId) {
        Map<Object, Object> hash = redis.opsForHash().entries(familyKey(familyId));
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(toFamily(familyId, hash));
        } catch (RuntimeException e) {
            // 값이 깨진 family 는 믿을 수 없으니 지우고 없는 것으로 본다 (다시 로그인)
            log.warn("refresh token family 값이 올바르지 않아 지움: {}", e.getClass().getSimpleName());
            redis.delete(familyKey(familyId));
            return Optional.empty();
        }
    }

    @Override
    public boolean replace(RefreshFamily expected, RefreshFamily updated) {
        List<String> args = new ArrayList<>();
        args.add(expected.currentHash());
        args.add(millis(updated.expiresAt()));
        args.add(updated.familyId());
        args.addAll(fields(updated));
        Long result = redis.execute(REPLACE, List.of(familyKey(updated.familyId()), indexKey(updated.userId())),
                args.toArray());
        return result != null && result == 1L;
    }

    @Override
    public void delete(RefreshFamily family) {
        redis.execute(DELETE, List.of(familyKey(family.familyId()), indexKey(family.userId())), family.familyId());
    }

    @Override
    public void deleteAll(long userId) {
        redis.execute(DELETE_ALL, List.of(indexKey(userId)), FAMILY_PREFIX);
    }

    @Override
    public List<RefreshFamily> findAll(long userId, Instant now) {
        String index = indexKey(userId);
        redis.opsForZSet().removeRangeByScore(index, Double.NEGATIVE_INFINITY, now.toEpochMilli());
        Set<String> ids = redis.opsForZSet().range(index, 0, -1);
        if (ids == null) {
            return List.of();
        }
        List<RefreshFamily> result = new ArrayList<>();
        for (String id : ids) {
            Optional<RefreshFamily> family = find(id);
            if (family.isPresent()) {
                result.add(family.get());
            } else {
                redis.opsForZSet().remove(index, id); // 만료로 먼저 지워진 family
            }
        }
        return result;
    }

    private static List<String> fields(RefreshFamily f) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("userId", Long.toString(f.userId()));
        m.put("agent", f.agent());
        m.put("current", f.currentHash());
        m.put("previous", f.previousHash() == null ? "" : f.previousHash());
        m.put("previousValidUntil", f.previousValidUntil() == null ? "" : millis(f.previousValidUntil()));
        m.put("createdAt", millis(f.createdAt()));
        m.put("lastUsedAt", millis(f.lastUsedAt()));
        m.put("expiresAt", millis(f.expiresAt()));
        m.put("absoluteExpiresAt", millis(f.absoluteExpiresAt()));
        List<String> flat = new ArrayList<>();
        m.forEach((k, v) -> {
            flat.add(k);
            flat.add(v);
        });
        return flat;
    }

    private static RefreshFamily toFamily(String familyId, Map<Object, Object> h) {
        String previous = (String) h.get("previous");
        String previousValidUntil = (String) h.get("previousValidUntil");
        return new RefreshFamily(
                familyId,
                Long.parseLong((String) h.get("userId")),
                (String) h.get("agent"),
                (String) h.get("current"),
                previous == null || previous.isEmpty() ? null : previous,
                previousValidUntil == null || previousValidUntil.isEmpty() ? null : instant(previousValidUntil),
                instant((String) h.get("createdAt")),
                instant((String) h.get("lastUsedAt")),
                instant((String) h.get("expiresAt")),
                instant((String) h.get("absoluteExpiresAt")));
    }

    private static String familyKey(String familyId) {
        return FAMILY_PREFIX + familyId;
    }

    private static String indexKey(long userId) {
        return "auth:user:" + userId + ":families";
    }

    private static String millis(Instant instant) {
        return Long.toString(instant.toEpochMilli());
    }

    private static Instant instant(String millis) {
        return Instant.ofEpochMilli(Long.parseLong(millis));
    }
}
