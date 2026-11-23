package com.klmin.commerce.paymentservice.payment.adapter.out.persistence;

import com.github.f4b6a3.uuid.UuidCreator;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentSummaryResult;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 내 결제 목록 (최신순 커서 페이징, 본인 것만) */
@Testcontainers(disabledWithoutDocker = true)
class PaymentSummaryPersistenceAdapterTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private JdbcClient jdbc;
    private PaymentSummaryPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load()
                .clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        adapter = new PaymentSummaryPersistenceAdapter(dataSource);
    }

    @Test
    void newestFirst_withCursor_onlyMine() {
        UUID first = payment(1L, "APPROVED", null);
        UUID second = payment(1L, "DECLINED", "card declined");
        UUID third = payment(1L, "REFUNDED", "refunded: order cancelled");
        payment(2L, "APPROVED", null);

        List<PaymentSummaryResult> page1 = adapter.findByUser(1L, null, 2);
        List<PaymentSummaryResult> page2 = adapter.findByUser(1L, page1.getLast().paymentId(), 2);

        assertThat(page1).extracting(PaymentSummaryResult::paymentId).containsExactly(third, second);
        assertThat(page2).extracting(PaymentSummaryResult::paymentId).containsExactly(first);
        assertThat(page1.get(1).status()).isEqualTo("DECLINED");
        assertThat(page1.get(1).reason()).isEqualTo("card declined");
        assertThat(page1.get(1).amount()).isEqualTo(1_000L);
        assertThat(adapter.findByUser(3L, null, 20)).isEmpty();
    }

    private UUID payment(long userId, String status, String reason) {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        jdbc.sql("""
                        insert into payments (id, order_id, user_id, amount, status, reason, created_at, updated_at)
                        values (?, ?, ?, 1000, ?, ?, ?, ?)""")
                .params(id, UUID.randomUUID(), userId, status, reason, Timestamp.from(NOW), Timestamp.from(NOW))
                .update();
        return id;
    }
}
