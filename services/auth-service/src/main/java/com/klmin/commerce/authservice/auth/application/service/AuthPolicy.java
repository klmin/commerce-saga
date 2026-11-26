package com.klmin.commerce.authservice.auth.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 로그인 실패 제한 (ADR 0008 의 규칙, SAS 로그인 폼에서 그대로 쓴다). application.yml 의 {@code auth.policy}.
 * 토큰 수명은 SAS 클라이언트 설정({@code auth.oauth})
 *
 * @param loginMaxFailures 이만큼 실패하면 잠근다
 * @param loginLock        실패 횟수를 세는 창이자 잠금 시간 (첫 실패부터)
 */
@ConfigurationProperties("auth.policy")
public record AuthPolicy(int loginMaxFailures, Duration loginLock) {
}
