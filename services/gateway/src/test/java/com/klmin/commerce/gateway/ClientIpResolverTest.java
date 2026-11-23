package com.klmin.commerce.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "::1"));

    private static MockServerHttpRequest from(String peer, String... forwardedFor) {
        MockServerHttpRequest.BodyBuilder builder = MockServerHttpRequest.post("/auth/login")
                .remoteAddress(new InetSocketAddress(peer, 40000));
        if (forwardedFor.length > 0) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return builder.build();
    }

    @Test
    void 믿는_프록시_BFF_에서_오면_그_프록시가_넘긴_브라우저_IP_로_나눈다() {
        assertThat(resolver.resolve(from("10.1.2.3", "198.51.100.9"))).isEqualTo("198.51.100.9");
        assertThat(resolver.resolve(from("10.1.2.3", "203.0.113.5"))).isEqualTo("203.0.113.5");
        assertThat(resolver.resolve(from("::1", "2001:db8::7"))).isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void 여러_값이면_맨_오른쪽_믿는_프록시가_붙인_값만_쓴다() {
        // 앞쪽은 브라우저가 마음대로 보낼 수 있다
        assertThat(resolver.resolve(from("10.1.2.3", "1.1.1.1, 198.51.100.9"))).isEqualTo("198.51.100.9");
        assertThat(resolver.resolve(from("10.1.2.3", "1.1.1.1", "198.51.100.9"))).isEqualTo("198.51.100.9");
    }

    @Test
    void 믿지_않는_주소에서_온_X_Forwarded_For_는_무시하고_연결_주소로() {
        assertThat(resolver.resolve(from("203.0.113.50", "198.51.100.9"))).isEqualTo("203.0.113.50");
        assertThat(resolver.resolve(from("11.0.0.1", "198.51.100.9"))).isEqualTo("11.0.0.1");
    }

    @Test
    void 헤더가_없거나_IP_가_아니면_연결_주소로() {
        assertThat(resolver.resolve(from("10.1.2.3"))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(from("10.1.2.3", "evil.example"))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(from("10.1.2.3", "unknown"))).isEqualTo("10.1.2.3");
    }

    @Test
    void 설정이_비어_있으면_아무_프록시도_믿지_않는다() {
        ClientIpResolver none = new ClientIpResolver(List.of(""));
        assertThat(none.resolve(from("127.0.0.1", "198.51.100.9"))).isEqualTo("127.0.0.1");
    }

    @Test
    void CIDR_경계와_잘못된_설정() {
        ClientIpResolver narrow = new ClientIpResolver(List.of("172.16.0.0/12"));
        assertThat(narrow.resolve(from("172.31.255.255", "198.51.100.9"))).isEqualTo("198.51.100.9");
        assertThat(narrow.resolve(from("172.32.0.0", "198.51.100.9"))).isEqualTo("172.32.0.0");
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/33")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientIpResolver(List.of("bff.internal")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
