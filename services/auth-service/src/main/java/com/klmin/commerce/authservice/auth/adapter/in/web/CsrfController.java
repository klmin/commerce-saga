package com.klmin.commerce.authservice.auth.adapter.in.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 인증 화면(정적 SPA)이 로그인·가입 폼에 넣을 CSRF 토큰 (설계 문서 4절, ADR 0017).
 * 토큰은 SSO 세션({@code AUTH_SESSION})에 있고 쿠키로 내보내지 않는다(Gateway 의 {@code XSRF-TOKEN} 쿠키와 겹치지 않게).
 */
@RestController
class CsrfController {

    @GetMapping("/csrf")
    ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("parameterName", token.getParameterName(), "headerName", token.getHeaderName(),
                        "token", token.getToken()));
    }
}
