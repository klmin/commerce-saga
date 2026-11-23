package com.klmin.commerce.pointservice.point.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param signupBonus 가입 축하 포인트 (새 계정의 시작 잔액) */
@ConfigurationProperties("point.account")
public record PointAccountProperties(Long signupBonus) {

    public PointAccountProperties {
        if (signupBonus == null) {
            signupBonus = 1_000L;
        }
        if (signupBonus < 0) {
            throw new IllegalArgumentException("point.account.signup-bonus must not be negative");
        }
    }
}
