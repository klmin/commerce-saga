package com.klmin.commerce.authservice.auth.application.port.out;

import java.time.Instant;
import java.util.List;

/** 사용자별 기기 기록 (기기 id ↔ SSO 세션 id) */
public interface DevicePort {

    void save(long userId, DeviceRecord device);

    List<DeviceRecord> findAll(long userId);

    void remove(long userId, String deviceId);

    record DeviceRecord(String deviceId, String sessionId, String userAgent, Instant loginAt) {
    }
}
