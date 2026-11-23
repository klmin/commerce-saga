package com.klmin.commerce.authservice.auth.application.port.in;

public record SignUpCommand(String loginId, String password) {

    @Override
    public String toString() {
        return "SignUpCommand[loginId=" + loginId + ", password=***]";
    }
}
