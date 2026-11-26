package com.klmin.commerce.authservice.auth.adapter.in.oauth;

import com.klmin.commerce.authservice.auth.application.port.in.ManageAuthorizationsUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/**
 * OIDC 로그아웃({@code /connect/logout}) 때 그 사용자의 인가 기록을 지운다 (ADR 0017 "SSO 전체 로그아웃").
 * SSO 세션은 SAS 의 기본 로그아웃 처리가 끝낸다. 인가 기록까지 지워야 다른 화면(클라이언트)이 들고 있던
 * refresh token 으로도 더 갱신하지 못한다.
 */
@Component
public class SsoLogoutHandler implements LogoutHandler {

    private final ManageAuthorizationsUseCase useCase;

    SsoLogoutHandler(ManageAuthorizationsUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        Authentication user = authentication instanceof OidcLogoutAuthenticationToken logout
                && logout.getPrincipal() instanceof Authentication principal ? principal : authentication;
        if (user == null || !user.isAuthenticated()) {
            return;
        }
        try {
            useCase.revokeAll(Long.parseLong(user.getName()));
        } catch (NumberFormatException ignored) {
            // 우리 로그인 폼으로 로그인한 사용자가 아니다(지울 기록 없음)
        }
    }
}
