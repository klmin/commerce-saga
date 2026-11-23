package com.klmin.commerce.pointservice.point.application.port.in;

import java.time.Instant;

/** 가입한 사용자의 포인트 계정 열기 (auth.events UserRegistered) */
public record OpenPointAccountCommand(long userId, Instant registeredAt) {
}
