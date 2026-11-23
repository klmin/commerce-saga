package com.klmin.commerce.authservice.auth.application;

import com.klmin.commerce.authservice.common.error.TooManyRequestsException;

import java.time.Duration;

public class LoginLockedException extends TooManyRequestsException {

    public LoginLockedException(Duration retryAfter) {
        super("로그인 실패가 많아 잠시 후 다시 시도해 주세요.", retryAfter);
    }
}
