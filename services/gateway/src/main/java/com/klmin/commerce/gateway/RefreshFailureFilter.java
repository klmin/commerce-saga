package com.klmin.commerce.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * BFF 세션의 토큰을 더 쓸 수 없을 때(refresh token 만료, 다른 화면의 SSO 로그아웃으로 인가 기록 삭제 등) API 가 500 이 아니라
 * <b>401</b> 이 되게 한다 (ADR 0017). {@code TokenRelay} 가 갱신에 실패하면 Spring 은 세션의 토큰만 지우고 예외를 다시 던진다.
 * 여기서 BFF 세션을 끝내고 401 을 돌려주면 SPA 는 다른 401 과 같이 로그인 시작 주소로 화면을 옮긴다
 * (SSO 세션이 남아 있으면 화면 없이 다시 로그인된다).
 */
@Component
class RefreshFailureFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RefreshFailureFilter.class);

    @Override
    public int getOrder() {
        // Spring Security(-100) 안쪽, 라우팅(핸들러) 바깥
        return 0;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).onErrorResume(OAuth2AuthorizationException.class, e -> {
            if (exchange.getResponse().isCommitted()) {
                return Mono.error(e);
            }
            log.info("BFF 토큰 갱신 실패({}), 세션을 끝내고 401", e.getError().getErrorCode());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getSession().flatMap(WebSession::invalidate)
                    .then(exchange.getResponse().setComplete());
        });
    }
}
