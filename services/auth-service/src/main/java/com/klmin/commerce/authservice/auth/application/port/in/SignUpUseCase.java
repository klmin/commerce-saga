package com.klmin.commerce.authservice.auth.application.port.in;

import java.util.UUID;

public interface SignUpUseCase {

    /** @return 새 사용자 id */
    UUID signUp(SignUpCommand command);
}
