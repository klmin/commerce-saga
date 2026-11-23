package com.klmin.commerce.authservice.auth.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 토큰 수명과 로그인 제한 (ADR 0008). application.yml 의 {@code auth.policy}.
 *
 * @param refreshTokenTtl    쓰지 않으면 끝나는 시간 (refresh 마다 연장)
 * @param refreshAbsoluteTtl 처음 로그인부터 이 시간이 지나면 연장과 상관없이 끝난다
 * @param refreshGrace       회전 직후 직전 secret 을 동시 refresh 로 봐주는 시간
 * @param loginLock          실패 횟수를 세는 창이자 잠금 시간 (첫 실패부터)
 */
@ConfigurationProperties("auth.policy")
public record AuthPolicy(Duration accessTokenTtl, Duration refreshTokenTtl, Duration refreshAbsoluteTtl,
                         Duration refreshGrace, int loginMaxFailures, Duration loginLock) {
}
