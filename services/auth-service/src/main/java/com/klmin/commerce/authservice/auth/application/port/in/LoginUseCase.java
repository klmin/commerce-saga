package com.klmin.commerce.authservice.auth.application.port.in;

public interface LoginUseCase {

    TokenResult login(LoginCommand command);
}
