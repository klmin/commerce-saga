package com.klmin.commerce.gateway;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * rate limit 키에 쓸 클라이언트 IP (ADR 0008, 0011).
 * <p>기본은 TCP 연결 주소다. {@code X-Forwarded-For} 는 아무나 쓸 수 있으므로, 연결 주소가 믿는 프록시
 * ({@code gateway.trusted-proxies}, 예: 화면의 BFF 인 Next 서버)일 때만 그 헤더의 맨 오른쪽 값(그 프록시가 넘긴 주소)을 쓴다.
 * 그러지 않으면 Next 서버를 거친 모든 사용자의 로그인·refresh 가 Next 서버 주소 하나의 버킷을 나눠 쓴다.
 */
final class ClientIpResolver {

    // 주소 모양만 먼저 거른다. InetAddress.getByName 이 호스트 이름을 DNS 로 찾지 않게 한다
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private final List<Cidr> trustedProxies;

    ClientIpResolver(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Cidr::parse)
                .toList();
    }

    String resolve(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        InetAddress peer = remote.getAddress();
        if (trustedProxies.stream().anyMatch(cidr -> cidr.contains(peer))) {
            String forwarded = lastForwarded(request.getHeaders().get("X-Forwarded-For"));
            if (forwarded != null) {
                return forwarded;
            }
        }
        return peer.getHostAddress();
    }

    private static String lastForwarded(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String joined = String.join(",", values);
        InetAddress address = parseLiteral(joined.substring(joined.lastIndexOf(',') + 1).trim());
        return address == null ? null : address.getHostAddress();
    }

    private static InetAddress parseLiteral(String value) {
        if (!IP_LITERAL.matcher(value).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** {@code 10.0.0.0/8}, {@code ::1/128} 또는 주소 하나 */
    record Cidr(byte[] network, int prefix) {

        static Cidr parse(String value) {
            int slash = value.indexOf('/');
            InetAddress address = parseLiteral(slash < 0 ? value : value.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException("gateway.trusted-proxies 에 IP 가 아닌 값: " + value);
            }
            int bits = address.getAddress().length * 8;
            int prefix;
            try {
                prefix = slash < 0 ? bits : Integer.parseInt(value.substring(slash + 1));
            } catch (NumberFormatException e) {
                prefix = -1;
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("gateway.trusted-proxies 의 prefix 가 잘못됨: " + value);
            }
            return new Cidr(address.getAddress(), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (bytes[full] & mask) == (network[full] & mask);
        }
    }
}
