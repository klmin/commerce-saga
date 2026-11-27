package com.klmin.commerce.authservice.auth.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 사용자별 기기 기록 (기기 id ↔ SSO 세션 id) */
public interface DevicePort {

    void save(UUID userId, DeviceRecord device);

    List<DeviceRecord> findAll(UUID userId);

    void remove(UUID userId, String deviceId);

    /** 기기 기록 보존 기간을 지금부터 다시 건다 (새 refresh token 이 그 뒤까지 살 수 있으므로) */
    void extend(UUID userId);

    record DeviceRecord(String deviceId, String sessionId, String userAgent, Instant loginAt) {
    }
}
