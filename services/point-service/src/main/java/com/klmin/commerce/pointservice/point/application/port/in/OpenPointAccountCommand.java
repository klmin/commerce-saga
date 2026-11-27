package com.klmin.commerce.pointservice.point.application.port.in;

import java.time.Instant;
import java.util.UUID;

/** 가입한 사용자의 포인트 계정 열기 (auth.events UserRegistered) */
public record OpenPointAccountCommand(UUID userId, Instant registeredAt) {
}
