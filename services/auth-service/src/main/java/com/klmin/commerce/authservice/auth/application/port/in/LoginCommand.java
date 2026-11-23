package com.klmin.commerce.authservice.auth.application.port.in;

/** @param agent 로그인 기기 표시용 (User-Agent). 인증에는 쓰지 않는다 */
public record LoginCommand(String loginId, String password, String agent) {

    @Override
    public String toString() {
        return "LoginCommand[loginId=" + loginId + ", password=***, agent=" + agent + "]";
    }
}
