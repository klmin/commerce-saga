package com.klmin.commerce.gateway;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 화면(SPA)이 지금 로그인한 사용자를 알 때 쓴다 (5-3, ADR 0017). SPA 는 토큰을 모르므로 BFF 세션의 사용자를 여기서 읽는다.
 * 로그인하지 않았으면 다른 {@code /api/**} 처럼 401({@link GatewaySecurityConfig}) → SPA 가 로그인을 시작한다.
 * 서비스로 넘기지 않고 Gateway 가 답한다(어노테이션 컨트롤러가 Gateway 라우트보다 먼저 맞는다).
 * 역할은 메뉴를 보이고 숨기는 데만 쓴다. 권한 검사는 Gateway·서비스가 토큰으로 한다
 */
@RestController
class MeController {

    @GetMapping("/api/me")
    Me me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .distinct()
                .sorted()
                .toList();
        return new Me(Long.parseLong(authentication.getName()), roles);
    }

    record Me(long userId, List<String> roles) {
    }
}
