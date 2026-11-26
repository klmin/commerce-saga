package com.klmin.commerce.authservice.auth.application.port.in;

/**
 * 아이디·비밀번호 확인 (SAS 로그인 폼 {@code POST /login}, ADR 0017). 토큰은 만들지 않는다(SAS 가 만든다).
 * 실패 제한: 아이디별 실패 횟수, 넘으면 잠금 (ADR 0008 의 규칙 그대로).
 */
public interface AuthenticateUseCase {

    /**
     * @throws com.klmin.commerce.authservice.auth.application.InvalidCredentialsException 없는 아이디·틀린 비밀번호·정지 계정
     * @throws com.klmin.commerce.authservice.auth.application.LoginLockedException          실패가 너무 많아 잠김
     */
    AuthenticatedUserResult authenticate(AuthenticateCommand command);
}
