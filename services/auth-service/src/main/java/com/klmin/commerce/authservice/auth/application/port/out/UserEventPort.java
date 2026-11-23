package com.klmin.commerce.authservice.auth.application.port.out;

import java.time.Instant;

/** 회원 이벤트 발행. 호출한 트랜잭션 안에서 outbox 에 남긴다 (사용자 저장과 원자적) */
public interface UserEventPort {

    void userRegistered(long userId, Instant registeredAt);
}
