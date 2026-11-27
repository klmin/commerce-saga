package com.klmin.commerce.orderservice.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    private static Jwt token(String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").claim("roles", "USER");
        return (subject == null ? builder : builder.subject(subject)).build();
    }

    @Test
    void subjectMustBeCanonicalUuid() {
        assertThat(SecurityConfig.validateSubject(token("01900000-0000-7000-8001-000000000001")).hasErrors()).isFalse();

        assertThat(SecurityConfig.validateSubject(token("7")).hasErrors()).isTrue();
        assertThat(SecurityConfig.validateSubject(token("not-a-uuid")).hasErrors()).isTrue();
        assertThat(SecurityConfig.validateSubject(token("1-0-0-0-1")).hasErrors()).isTrue();
        assertThat(SecurityConfig.validateSubject(token("01900000-0000-7000-8001-00000000000A")).hasErrors()).isTrue();
        assertThat(SecurityConfig.validateSubject(token(null)).hasErrors()).isTrue();
    }
}
