package com.klmin.commerce.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;

/**
 * rate limit 의 키 (ADR 0008, RedisRateLimiter = Redis 토큰 버킷).
 로그인한 요청은 사용자(sub)별, 토큰 없이 열린 경로는 IP 별.
 * IP 는 TCP 연결 주소다. 연결이 믿는 프록시({@code gateway.trusted-proxies}, 앞단 Caddy·Envoy)에서 왔을 때만
 * 그 프록시가 넘긴 {@code X-Forwarded-For} 를 쓴다 ({@link ClientIpResolver}).
 */
@Configuration(proxyBeanMethods = false)
class RateLimitKeys {

    @Bean
    ClientIpResolver clientIpResolver(@Value("${gateway.trusted-proxies:}") List<String> trustedProxies) {
        return new ClientIpResolver(trustedProxies);
    }

    @Bean
    @Primary
    KeyResolver userKeyResolver(ClientIpResolver clientIp) {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(sub -> "user:" + sub)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp.resolve(exchange.getRequest())));
    }
}
