package com.klmin.commerce.authservice.auth.application.port.in;

public record AuthenticateCommand(String loginId, String password) {

    @Override
    public String toString() {
        return "AuthenticateCommand[loginId=" + loginId + ", password=***]";
    }
}
