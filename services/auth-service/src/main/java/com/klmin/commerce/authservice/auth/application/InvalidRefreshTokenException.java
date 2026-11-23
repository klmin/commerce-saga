package com.klmin.commerce.authservice.auth.application;

import com.klmin.commerce.authservice.common.error.AuthenticationFailedException;

public class InvalidRefreshTokenException extends AuthenticationFailedException {

    public InvalidRefreshTokenException() {
        super("다시 로그인해 주세요.");
    }
}
