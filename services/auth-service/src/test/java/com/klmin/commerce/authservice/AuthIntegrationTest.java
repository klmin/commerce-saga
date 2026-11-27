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

    /** 개발용 시드의 관리자 (R__dev_seed_users.sql, 다른 서비스 시드와 같은 값) */
    private static final UUID ADMIN = UUID.fromString("01900000-0000-7000-8001-000000000100");

    @Test
    void 개발용_시드_관리자는_ADMIN_이고_같은_id_의_다른_사용자에게는_역할을_주지_않는다() {
        assertThat(roles(ADMIN)).containsExactlyInAnyOrder("USER", "ADMIN");
        try {
            // 관리자 id 를 다른 아이디의 일반 사용자가 가진 DB 를 만든다
            jdbc.sql("delete from user_roles where user_id = ?").param(ADMIN).update();
            jdbc.sql("update users set login_id = 'mallory' where id = ?").param(ADMIN).update();
            jdbc.sql("insert into user_roles (user_id, role) values (?, 'USER')").param(ADMIN).update();

            runSeed();

            assertThat(roles(ADMIN)).containsExactly("USER");
        } finally {
            jdbc.sql("update users set login_id = 'admin' where id = ?").param(ADMIN).update();
            jdbc.sql("insert into user_roles (user_id, role) values (?, 'ADMIN') on conflict do nothing")
                    .param(ADMIN).update();
        }
    }

    @Test
    void 시드보다_먼저_admin_아이디로_가입한_다른_사용자에게는_ADMIN_을_주지_않는다() {
        UUID mallory = UUID.randomUUID();
        try {
            // 시드 관리자가 없고 다른 id 의 사용자가 아이디 admin 을 먼저 차지한 DB 를 만든다
            jdbc.sql("delete from user_roles where user_id = ?").param(ADMIN).update();
            jdbc.sql("delete from users where id = ?").param(ADMIN).update();
            jdbc.sql("""
                            insert into users (id, login_id, password_hash, status, created_at, updated_at)
                            values (?, 'admin', 'x', 'ACTIVE', now(), now())""")
                    .param(mallory).update();
            jdbc.sql("insert into user_roles (user_id, role) values (?, 'USER')").param(mallory).update();

            runSeed();

            assertThat(roles(mallory)).containsExactly("USER");
            assertThat(roles(ADMIN)).isEmpty();
        } finally {
            jdbc.sql("delete from user_roles where user_id = ?").param(mallory).update();
            jdbc.sql("delete from users where id = ?").param(mallory).update();
            runSeed();
        }
        assertThat(roles(ADMIN)).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    private void runSeed() {
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/dev-seed/R__dev_seed_users.sql")).execute(dataSource);
    }

    private List<String> roles(UUID userId) {
        return jdbc.sql("select role from user_roles where user_id = ?").param(userId).query(String.class).list();
    }

    @Test
    void 가입하면_같은_트랜잭션에서_UserRegistered_가_outbox_에_남고_중복_가입은_남기지_않는다() {
        UUID userId = signUp.signUp(new SignUpCommand(loginId, "password1"));
        assertThat(userId.version()).isEqualTo(7);
        assertThatThrownBy(() -> signUp.signUp(new SignUpCommand(loginId, "password2")))
                .isInstanceOf(DuplicateLoginIdException.class);

        var rows = jdbc.sql("""
                        select aggregatetype, type, payload::text as payload from outbox
                        where aggregateid = ?""")
                .param(userId.toString())
                .query((rs, n) -> rs.getString("aggregatetype") + "|" + rs.getString("type") + "|"
                        + rs.getString("payload"))
                .list();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).startsWith("auth.events|UserRegistered|")
                .contains("\"userId\": \"" + userId + "\"").contains("\"registeredAt\": \"20");
    }
}
