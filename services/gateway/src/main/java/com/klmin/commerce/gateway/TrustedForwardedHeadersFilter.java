package com.klmin.commerce.gateway;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 믿는 프록시({@code gateway.trusted-proxies})에서 온 요청만 {@code X-Forwarded-Proto}·{@code -Host}·{@code -Port} 로
 * 요청 주소를 공개 주소로 바꾼다 (설계 문서 7-3). OAuth2 로그인 콜백은 "지금 요청 주소" 가 등록한 redirect URI 와 같은지 보는데,
 * TLS 를 앞단에서 끝내면 Gateway 는 http 로 받기 때문이다.
 * <p>
 * Spring 의 기본 처리({@code forwarded.strategy=framework})를 쓰지 않는 이유: 아무 연결의 헤더나 믿고, 연결 주소까지
 * {@code X-Forwarded-For} 맨 왼쪽(브라우저가 쓸 수 있는 값)으로 바꿔 rate limit 의 IP 판단({@link ClientIpResolver})이 무너진다.
 * 여기서는 연결 주소와 {@code X-Forwarded-For} 를 건드리지 않는다.
 */
@Component
class TrustedForwardedHeadersFilter implements WebFilter, Ordered {

    private final ClientIpResolver clientIp;

    TrustedForwardedHeadersFilter(ClientIpResolver clientIp) {
        this.clientIp = clientIp;
    }

    @Override
    public int getOrder() {
        // Spring Security(WebFilterChainProxy) 보다 먼저
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!clientIp.fromTrustedProxy(request)) {
            return chain.filter(exchange);
        }
        HttpHeaders headers = request.getHeaders();
        String proto = first(headers.getFirst("X-Forwarded-Proto"));
        String host = first(headers.getFirst("X-Forwarded-Host"));
        String port = first(headers.getFirst("X-Forwarded-Port"));
        if (proto == null && host == null && port == null) {
            return chain.filter(exchange);
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromUri(request.getURI());
        try {
            if (proto != null && (proto.equals("http") || proto.equals("https"))) {
                uri.scheme(proto);
            }
            if (host != null) {
                int colon = host.lastIndexOf(':');
                boolean hasPort = colon > 0 && host.indexOf(']') < colon;
                uri.host(hasPort ? host.substring(0, colon) : host);
                uri.port(hasPort ? Integer.parseInt(host.substring(colon + 1)) : -1);
            }
            if (port != null) {
                uri.port(Integer.parseInt(port));
            }
            URI publicUri = uri.build(true).toUri();
            return chain.filter(exchange.mutate().request(request.mutate().uri(publicUri).build()).build());
        } catch (IllegalArgumentException e) {
            // 숫자가 아닌 포트·잘못된 호스트: 헤더를 무시하고 원래 주소로
            return chain.filter(exchange);
        }
    }

    // 프록시를 여럿 거치면 쉼표로 이어진다. 맨 앞이 브라우저와 맞닿은 프록시의 값
    private static String first(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int comma = value.indexOf(',');
        return (comma < 0 ? value : value.substring(0, comma)).strip();
    }
}
