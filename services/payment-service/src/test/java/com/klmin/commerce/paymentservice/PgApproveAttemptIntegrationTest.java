package com.klmin.commerce.paymentservice;

import com.klmin.commerce.paymentservice.payment.application.port.out.PgApproveAttemptPort;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/** PG 승인 호출 예약·봉인 (PROGRESS 4-4절 ③): 봉인 뒤에는 예약할 수 없고, 다른 예약이 있으면 봉인할 수 없다 */
@SpringBootTest(properties = {"management.logging.export.enabled=false", "idempotency.cleanup.enabled=false"})
@Import({TestcontainersConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class PgApproveAttemptIntegrationTest {

    @Autowired
    PgApproveAttemptPort attempts;

    @Autowired
    TransactionTemplate tx;

    @Test
    void claimsCount_andSealOnlyWhenSingleClaim() {
        UUID orderId = UUID.randomUUID();

        assertThat(attempts.claim(orderId)).hasValue(1);
        assertThat(attempts.claim(orderId)).hasValue(2);
        assertThat(attempts.sealIfOnlyClaim(orderId)).isFalse();
    }

    @Test
    void sealed_blocksLaterClaims() {
        UUID orderId = UUID.randomUUID();

        attempts.claim(orderId);
        assertThat(attempts.sealIfOnlyClaim(orderId)).isTrue();

        assertThat(attempts.claim(orderId)).isEmpty();
        assertThat(attempts.sealIfOnlyClaim(orderId)).isFalse();
    }

    @Test
    void survivesCallerRollback() {
        UUID orderId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            attempts.claim(orderId);
            status.setRollbackOnly();
        });

        assertThat(attempts.claim(orderId)).hasValue(2);
    }

    /** 봉인과 다른 처리의 예약이 동시에 와도 둘 다 성공하는 일은 없다(봉인 성공 → 예약 실패, 예약 성공 → 봉인 실패) */
    @RepeatedTest(20)
    void concurrentSealAndClaim_neverBothSucceed() throws Exception {
        UUID orderId = UUID.randomUUID();
        attempts.claim(orderId);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Boolean> seal = CompletableFuture.supplyAsync(() -> {
            await(start);
            return attempts.sealIfOnlyClaim(orderId);
        });
        CompletableFuture<OptionalInt> claim = CompletableFuture.supplyAsync(() -> {
            await(start);
            return attempts.claim(orderId);
        });
        start.countDown();

        boolean sealed = seal.get();
        OptionalInt claimed = claim.get();
        assertThat(sealed && claimed.isPresent()).isFalse();
        assertThat(sealed || claimed.isPresent()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
