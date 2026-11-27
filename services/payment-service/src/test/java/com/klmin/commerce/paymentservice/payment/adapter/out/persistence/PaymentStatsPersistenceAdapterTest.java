package com.klmin.commerce.paymentservice.payment.adapter.out.persistence;

import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentStatsResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 결제 보정 대상 통계 집계 쿼리 (메트릭 commerce_payment_*) */
@Testcontainers(disabledWithoutDocker = true)
class PaymentStatsPersistenceAdapterTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private JdbcClient jdbc;
    private PaymentStatsPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load()
                .clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        adapter = new PaymentStatsPersistenceAdapter(dataSource);
    }

    @Test
    void unknownAge_countsFromCreation_evenAfterCancelRequestUpdatesRow() {
        // PG 타임아웃 90초 전, 취소 요청 10초 전 → UNKNOWN 나이는 90초 (취소 요청이 나이를 줄이지 않는다)
        payment("UNKNOWN", true, NOW.minusSeconds(90), NOW.minusSeconds(10));
        payment("UNKNOWN", false, NOW.minusSeconds(20), NOW.minusSeconds(20));
        // 승인 후 취소 요청 40초 전 → 환불 대기
        payment("APPROVED", true, NOW.minusSeconds(300), NOW.minusSeconds(40));
        payment("APPROVED", false, NOW.minusSeconds(300), NOW.minusSeconds(300));
        payment("REFUNDED", true, NOW.minusSeconds(300), NOW.minusSeconds(200));

        PaymentStatsResult stats = adapter.loadPaymentStats(NOW);

        assertThat(stats).isEqualTo(new PaymentStatsResult(2, Duration.ofSeconds(90), 1, Duration.ofSeconds(40)));
    }

    @Test
    void nothingPending_isZero() {
        payment("APPROVED", false, NOW.minusSeconds(300), NOW.minusSeconds(300));

        assertThat(adapter.loadPaymentStats(NOW))
                .isEqualTo(new PaymentStatsResult(0, Duration.ZERO, 0, Duration.ZERO));
    }

    private void payment(String status, boolean cancelRequested, Instant createdAt, Instant updatedAt) {
        jdbc.sql("""
                        insert into payments (id, order_id, user_id, amount, status, cancel_requested, created_at, updated_at)
                        values (?, ?, ?, 1000, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status, cancelRequested, Timestamp.from(createdAt),
                        Timestamp.from(updatedAt))
                .update();
    }
}
