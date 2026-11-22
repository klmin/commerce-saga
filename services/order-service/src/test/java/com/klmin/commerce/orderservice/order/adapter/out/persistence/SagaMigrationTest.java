package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V2 마이그레이션이 1단계에서 진행 중이던 주문을 Saga 로 이관하는지 (V1 데이터 -> V2) */
@Testcontainers(disabledWithoutDocker = true)
class SagaMigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Test
    void v2_migratesPendingPhase1Orders_andMapsFinishedStatuses() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcClient jdbc = JdbcClient.create(dataSource);
        flyway(dataSource, "1").migrate();

        UUID pending = UUID.randomUUID();
        UUID confirmed = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        insertPhase1Order(jdbc, pending, "PENDING", 2_000L);
        insertPhase1Order(jdbc, confirmed, "CONFIRMED", 1_000L);
        insertPhase1Order(jdbc, rejected, "REJECTED", 500L);

        flyway(dataSource, "2").migrate();

        Map<String, Object> saga = jdbc.sql("select * from order_sagas where order_id = ?").param(pending)
                .query().singleRow();
        assertThat(saga.get("state")).isEqualTo("RESERVING_STOCK");
        assertThat(saga.get("paid_amount")).isEqualTo(2_000L);
        assertThat(saga.get("point_amount")).isEqualTo(0L);
        assertThat(jdbc.sql("select count(*) from order_saga_history where order_id = ?").param(pending)
                .query(Long.class).single()).isEqualTo(1L);
        // 이미 끝난 주문은 상태값만 바뀌고 Saga 는 만들지 않는다
        assertThat(status(jdbc, confirmed)).isEqualTo("COMPLETED");
        assertThat(status(jdbc, rejected)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("select count(*) from order_sagas").query(Long.class).single()).isEqualTo(1L);
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private static void insertPhase1Order(JdbcClient jdbc, UUID id, String status, long total) {
        jdbc.sql("""
                        insert into orders (id, user_id, status, total_amount, reject_reason, created_at, updated_at, version)
                        values (?, 1, ?, ?, null, now(), now(), 0)
                        """)
                .params(id, status, total)
                .update();
    }

    private static String status(JdbcClient jdbc, UUID id) {
        return jdbc.sql("select status from orders where id = ?").param(id).query(String.class).single();
    }
}
