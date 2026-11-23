package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.application.port.in.SagaStatsResult;
import com.klmin.commerce.orderservice.order.application.port.in.SagaStatsResult.StateResult;
import com.klmin.commerce.orderservice.order.domain.SagaState;
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

/** Saga 통계 집계 쿼리 (메트릭 commerce_saga_*) */
@Testcontainers(disabledWithoutDocker = true)
class SagaStatsPersistenceAdapterTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private JdbcClient jdbc;
    private SagaStatsPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load()
                .clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        adapter = new SagaStatsPersistenceAdapter(dataSource);
    }

    @Test
    void countsActiveSagasByState_withOldestAge_andTimeoutEvents() {
        UUID oldPaying = saga(SagaState.PAYING, NOW.minusSeconds(90));
        saga(SagaState.PAYING, NOW.minusSeconds(5));
        saga(SagaState.REFUNDING_POINT, NOW.minusSeconds(30));
        saga(SagaState.COMPLETED, NOW.minusSeconds(600));
        saga(SagaState.CANCELLED, NOW.minusSeconds(600));
        history(oldPaying, 1, "TIMEOUT_RESEND");
        history(oldPaying, 2, "TIMEOUT_RESEND");
        history(oldPaying, 3, "TIMEOUT_GIVE_UP");
        history(oldPaying, 4, "PAYMENT_CANCELLED");

        SagaStatsResult stats = adapter.loadSagaStats(NOW);

        assertThat(stats.active()).containsOnlyKeys(SagaState.PAYING, SagaState.REFUNDING_POINT);
        assertThat(stats.active().get(SagaState.PAYING)).isEqualTo(new StateResult(2, Duration.ofSeconds(90)));
        assertThat(stats.active().get(SagaState.REFUNDING_POINT)).isEqualTo(new StateResult(1, Duration.ofSeconds(30)));
        assertThat(stats.timeoutResends()).isEqualTo(2);
        assertThat(stats.timeoutGiveUps()).isEqualTo(1);
    }

    @Test
    void noSagas_isEmpty() {
        SagaStatsResult stats = adapter.loadSagaStats(NOW);

        assertThat(stats.active()).isEmpty();
        assertThat(stats.timeoutResends()).isZero();
        assertThat(stats.timeoutGiveUps()).isZero();
    }

    private UUID saga(SagaState state, Instant createdAt) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(createdAt);
        jdbc.sql("""
                        insert into orders (id, user_id, status, total_amount, created_at, updated_at, version)
                        values (?, 1, 'PENDING', 1000, ?, ?, 0)""")
                .params(id, at, at).update();
        jdbc.sql("""
                        insert into order_sagas (order_id, user_id, point_amount, paid_amount, state, point_used,
                                                 created_at, updated_at, version)
                        values (?, 1, 0, 1000, ?, false, ?, ?, 0)""")
                .params(id, state.name(), at, at).update();
        return id;
    }

    private void history(UUID orderId, int seq, String event) {
        jdbc.sql("""
                        insert into order_saga_history (order_id, seq, event, state, occurred_at)
                        values (?, ?, ?, 'PAYING', now())""")
                .params(orderId, seq, event).update();
    }
}
