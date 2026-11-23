package com.klmin.commerce.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

/**
 * rate limit 의 키 (ADR 0008, RedisRateLimiter = Redis 토큰 버킷).
 * <ul>
 *   <li>{@code userKeyResolver}: 로그인한 요청은 사용자(sub)별. 토큰 없이 열린 경로는 IP 별</li>
 *   <li>{@code clientIpKeyResolver}: {@code /auth/**} 는 IP 별 (로그인 대입은 auth-service 가 아이디별로도 막는다)</li>
 * </ul>
 * IP 는 TCP 연결의 주소다. {@code X-Forwarded-For} 는 아무나 쓸 수 있어 믿지 않는다. 앞에 edge(Ingress)가 생기면
 * 그 주소만 믿고 헤더를 읽도록 바꾼다(k8s 단계).
 */
@Configuration(proxyBeanMethods = false)
class RateLimitKeys {

    @Bean
    @Primary
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(sub -> "user:" + sub)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange)));
    }

    @Bean
    KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.just("ip:" + clientIp(exchange));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
