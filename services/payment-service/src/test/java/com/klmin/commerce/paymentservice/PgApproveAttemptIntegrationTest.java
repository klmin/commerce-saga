package com.klmin.commerce.paymentservice;

import com.klmin.commerce.paymentservice.payment.application.port.out.PgApproveAttemptPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PG 승인 시도 기록: 처음이면 false, 이미 있으면 true. 호출한 트랜잭션이 롤백돼도 남는다 (PROGRESS 4-4절 ③) */
@SpringBootTest(properties = {"management.logging.export.enabled=false", "idempotency.cleanup.enabled=false"})
@Import({TestcontainersConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class PgApproveAttemptIntegrationTest {

    @Autowired
    PgApproveAttemptPort attempts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void recordsOnce_andTellsWhetherAttemptedBefore() {
        UUID orderId = UUID.randomUUID();

        assertThat(attempts.recordAttempt(orderId)).isFalse();
        assertThat(attempts.recordAttempt(orderId)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from pg_approve_attempts where order_id = ?",
                Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void survivesCallerRollback() {
        UUID orderId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            attempts.recordAttempt(orderId);
            status.setRollbackOnly();
        });

        assertThat(attempts.recordAttempt(orderId)).isTrue();
    }
}
