package com.klmin.commerce.authservice;

import com.klmin.commerce.authservice.auth.application.DuplicateLoginIdException;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpCommand;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Postgres + Redis 를 띄워 가입(outbox)·개발용 시드를 실제 저장소로 확인한다.
 * 로그인·토큰·SSO 는 {@link OAuthFlowIntegrationTest} (기존 자체 JWT API 는 후속 5-3c 에서 지웠다)
 */
// outbox relay 는 켜 두되 돌지 않게 (Kafka 없이 outbox 행을 확인한다)
// 로컬 개발용 키 파일(사용자 홈)을 만들지 않게 임시 키로
// 클라이언트 시크릿은 테스트용 값으로 (로컬 개발용 파일을 만들지 않게)
@SpringBootTest(properties = {"auth.cookie.secure=false", "outbox.relay.fixed-delay=1h", "auth.jwt.dev-key-file=",
        "auth.oauth.secret-dev-dir=", "auth.oauth.clients.gateway-web.secret=test-web-secret",
        "auth.oauth.clients.gateway-admin.secret=test-admin-secret"})
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AuthIntegrationTest {

    @Autowired
    SignUpUseCase signUp;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    javax.sql.DataSource dataSource;

    private String loginId;

    @BeforeEach
    void setUp() {
        loginId = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void 개발용_시드_관리자는_ADMIN_이고_같은_id_의_다른_사용자에게는_역할을_주지_않는다() {
        assertThat(roles(100)).containsExactlyInAnyOrder("USER", "ADMIN");
        try {
            // 시드보다 먼저 가입한 일반 사용자가 id 100 을 차지한 DB 를 만든다
            jdbc.sql("delete from user_roles where user_id = 100").update();
            jdbc.sql("update users set login_id = 'mallory' where id = 100").update();
            jdbc.sql("insert into user_roles (user_id, role) values (100, 'USER')").update();

            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/dev-seed/R__dev_seed_users.sql")).execute(dataSource);

            assertThat(roles(100)).containsExactly("USER");
        } finally {
            jdbc.sql("update users set login_id = 'admin' where id = 100").update();
            jdbc.sql("insert into user_roles (user_id, role) values (100, 'ADMIN') on conflict do nothing").update();
        }
    }

    private List<String> roles(long userId) {
        return jdbc.sql("select role from user_roles where user_id = ?").param(userId).query(String.class).list();
    }

    @Test
    void 가입하면_같은_트랜잭션에서_UserRegistered_가_outbox_에_남고_중복_가입은_남기지_않는다() {
        long userId = signUp.signUp(new SignUpCommand(loginId, "password1"));
        assertThatThrownBy(() -> signUp.signUp(new SignUpCommand(loginId, "password2")))
                .isInstanceOf(DuplicateLoginIdException.class);

        var rows = jdbc.sql("""
                        select aggregatetype, type, payload::text as payload from outbox
                        where aggregateid = ?""")
                .param(Long.toString(userId))
                .query((rs, n) -> rs.getString("aggregatetype") + "|" + rs.getString("type") + "|"
                        + rs.getString("payload"))
                .list();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).startsWith("auth.events|UserRegistered|")
                .contains("\"userId\": " + userId).contains("\"registeredAt\": \"20");
    }
}
