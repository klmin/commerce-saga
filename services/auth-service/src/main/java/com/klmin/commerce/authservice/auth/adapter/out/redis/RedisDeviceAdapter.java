package com.klmin.commerce.authservice.auth.adapter.out.redis;

import com.klmin.commerce.authservice.auth.application.port.out.DevicePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 사용자별 기기 {@code auth:device:<userId>} (hash: 기기 id → "세션 id \n 로그인 시각(ms) \n 브라우저"). 브라우저 문자열은
 * 서비스가 제어 문자를 빼 두어 줄바꿈이 없다. 키 만료는 refresh token 수명보다 하루 길게, 로그인할 때마다 다시 건다
 * (그 뒤엔 어느 기기도 로그인돼 있을 수 없다). 끝난 기기는 목록을 볼 때 지운다
 */
@Component
class RedisDeviceAdapter implements DevicePort {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    RedisDeviceAdapter(StringRedisTemplate redis,
                       @Value("${auth.oauth.refresh-token-ttl:14d}") Duration refreshTokenTtl) {
        this.redis = redis;
        this.ttl = refreshTokenTtl.plusDays(1);
    }

    @Override
    public void save(long userId, DeviceRecord device) {
        String key = key(userId);
        redis.opsForHash().put(key, device.deviceId(),
                device.sessionId() + "\n" + device.loginAt().toEpochMilli() + "\n" + device.userAgent());
        redis.expire(key, ttl);
    }

    @Override
    public List<DeviceRecord> findAll(long userId) {
        List<DeviceRecord> result = new ArrayList<>();
        for (Map.Entry<Object, Object> e : redis.opsForHash().entries(key(userId)).entrySet()) {
            String[] parts = e.getValue().toString().split("\n", 3);
            if (parts.length < 3) {
                redis.opsForHash().delete(key(userId), e.getKey()); // 깨진 값
                continue;
            }
            try {
                result.add(new DeviceRecord(e.getKey().toString(), parts[0],
                        parts[2], Instant.ofEpochMilli(Long.parseLong(parts[1]))));
            } catch (NumberFormatException ex) {
                redis.opsForHash().delete(key(userId), e.getKey());
            }
        }
        return result;
    }

    @Override
    public void remove(long userId, String deviceId) {
        redis.opsForHash().delete(key(userId), deviceId);
    }

    private static String key(long userId) {
        return "auth:device:" + userId;
    }
}
