package com.klmin.commerce.authservice.auth.adapter.in.web;

import com.klmin.commerce.authservice.common.error.AuthenticationFailedException;

/** 서명은 맞지만 내용(sub)이 사용자 id 가 아닌 access token (401) */
class InvalidAccessTokenException extends AuthenticationFailedException {

    InvalidAccessTokenException() {
        super("다시 로그인해 주세요.");
    }
}
