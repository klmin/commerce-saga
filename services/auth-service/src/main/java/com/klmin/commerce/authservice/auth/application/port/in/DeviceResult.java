package com.klmin.commerce.authservice.auth.application.port.in;

import java.time.Instant;

/** 기기 목록 한 줄. SSO 세션 id 는 내보내지 않는다(쿠키 값이라). id 는 기기 id */
public record DeviceResult(String id, boolean current, String userAgent, Instant loginAt, Instant lastSeenAt) {
}
