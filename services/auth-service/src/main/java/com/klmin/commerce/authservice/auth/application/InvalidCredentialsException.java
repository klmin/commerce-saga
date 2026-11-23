package com.klmin.commerce.authservice.auth.application;

import com.klmin.commerce.authservice.common.error.AuthenticationFailedException;

public class InvalidCredentialsException extends AuthenticationFailedException {

    public InvalidCredentialsException() {
        super("아이디 또는 비밀번호가 올바르지 않습니다.");
    }
}
