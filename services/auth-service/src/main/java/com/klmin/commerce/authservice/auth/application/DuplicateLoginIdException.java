package com.klmin.commerce.authservice.auth.application;

import com.klmin.commerce.authservice.common.error.ConflictException;

public class DuplicateLoginIdException extends ConflictException {

    public DuplicateLoginIdException() {
        super("이미 사용 중인 아이디입니다.");
    }
}
