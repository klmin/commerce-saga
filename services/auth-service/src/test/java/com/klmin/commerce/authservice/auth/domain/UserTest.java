package com.klmin.commerce.authservice.auth.domain;

import com.klmin.commerce.authservice.common.error.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");
    private static final UserId ID = UserId.of(UUID.fromString("01900000-0000-7000-8001-000000000007"));

    @Test
    void 새_회원은_USER_역할_하나에_활성이고_아이디는_소문자로_맞춘다() {
        User user = User.register(ID, "  Alice_01 ", "hash", NOW);

        assertThat(user.loginId()).isEqualTo("alice_01");
        assertThat(user.roles()).containsExactly(Role.USER);
        assertThat(user.isActive()).isTrue();
    }

    @Test
    void 회원_id_는_비어_있을_수_없다() {
        assertThatThrownBy(() -> User.register(null, "alice", "hash", NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> UserId.of(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "has space", "한글아이디", "dash-not-allowed", "a234567890123456789012345678901"})
    void 아이디_규칙에_맞지_않으면_거부(String loginId) {
        assertThatThrownBy(() -> User.register(ID, loginId, "hash", NOW)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void 비밀번호는_8자_이상_64자_이하_72바이트_이하() {
        PasswordPolicy.validate("12345678");
        PasswordPolicy.validate("a".repeat(64));
        assertThatThrownBy(() -> PasswordPolicy.validate("1234567")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(65))).isInstanceOf(DomainValidationException.class);
        // 한글 25자 = 75바이트: BCrypt 가 앞 72바이트만 써서 뒤가 무시되므로 거부
        assertThatThrownBy(() -> PasswordPolicy.validate("가".repeat(25))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate(null)).isInstanceOf(DomainValidationException.class);
    }
}
