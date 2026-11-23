package com.klmin.commerce.authservice.auth.application;

import com.klmin.commerce.authservice.common.error.NotFoundException;

public class SessionNotFoundException extends NotFoundException {

    public SessionNotFoundException() {
        super("로그인 기기를 찾을 수 없습니다.");
    }
}
