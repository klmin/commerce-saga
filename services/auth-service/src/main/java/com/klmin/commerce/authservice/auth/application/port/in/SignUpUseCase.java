package com.klmin.commerce.authservice.auth.application.port.in;

public interface SignUpUseCase {

    /** @return 새 사용자 id */
    long signUp(SignUpCommand command);
}
