package com.klmin.commerce.authservice.common.error;

/** 인증 실패 (401). 어떤 이유로 실패했는지(없는 아이디, 틀린 비밀번호, 재사용된 토큰)는 응답에 구분하지 않는다 */
public abstract class AuthenticationFailedException extends RuntimeException {

    protected AuthenticationFailedException(String message) {
        super(message);
    }
}
