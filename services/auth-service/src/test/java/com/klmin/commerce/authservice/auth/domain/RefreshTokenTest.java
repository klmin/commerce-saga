package com.klmin.commerce.authservice.auth.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest {

    private static final String FAMILY = "0b0f7e6e-3f5c-4c8e-9a7e-2f6d8c1b4a11";
    private static final String SECRET = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

    @Test
    void familyId_점_secret_을_읽고_쓴다() {
        RefreshToken token = RefreshToken.parse(FAMILY + "." + SECRET).orElseThrow();

        assertThat(token.familyId()).isEqualTo(FAMILY);
        assertThat(token.value()).isEqualTo(FAMILY + "." + SECRET);
        assertThat(token.secretHash()).hasSize(64).isNotEqualTo(SECRET);
        assertThat(token.toString()).doesNotContain(SECRET);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "no-dot", FAMILY + ".", "." + SECRET, FAMILY + ".short", "not-a-uuid." + SECRET,
            FAMILY + "." + SECRET + "x", FAMILY + "." + SECRET + ".extra"})
    void 형식이_틀리면_empty(String raw) {
        assertThat(RefreshToken.parse(raw)).isEmpty();
    }
}
