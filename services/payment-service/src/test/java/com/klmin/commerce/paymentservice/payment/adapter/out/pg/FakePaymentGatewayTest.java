package com.klmin.commerce.paymentservice.payment.adapter.out.pg;

import com.klmin.commerce.paymentservice.TestcontainersConfiguration;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Mode;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort.GatewayResult;
import com.klmin.commerce.paymentservice.payment.application.PaymentGatewayTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 가짜 PG 원장(fake_pg_transactions)으로 주문별 멱등이 재시작 후에도 유지되는지 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class FakePaymentGatewayTest {

    @Autowired
    DataSource dataSource;

    @Test
    void sameOrder_returnsFirstResult_evenAfterModeChange() {
        FakePaymentGateway gateway = new FakePaymentGateway(dataSource, Mode.APPROVE, 0);
        UUID orderId = UUID.randomUUID();

        GatewayResult first = gateway.approve(orderId, 1L, 100L);
        gateway.change(new Settings(Mode.DECLINE, 0));
        GatewayResult second = gateway.approve(orderId, 1L, 100L);

        assertThat(first.approved()).isTrue();
        assertThat(second).isEqualTo(first);
        assertThat(gateway.approve(UUID.randomUUID(), 1L, 100L).approved()).isFalse();
    }

    @Test
    void sameOrder_returnsFirstResult_afterRestart() {
        UUID orderId = UUID.randomUUID();
        GatewayResult beforeRestart = new FakePaymentGateway(dataSource, Mode.APPROVE, 0).approve(orderId, 1L, 100L);

        // 재시작: 새 인스턴스, 장애 주입 모드도 초기값(DECLINE)으로 바뀐 상황
        GatewayResult afterRestart = new FakePaymentGateway(dataSource, Mode.DECLINE, 0).approve(orderId, 1L, 100L);

        assertThat(afterRestart).isEqualTo(beforeRestart);
        assertThat(afterRestart.approved()).isTrue();
    }

    @Test
    void delayMode_waitsThenApproves() {
        FakePaymentGateway gateway = new FakePaymentGateway(dataSource, Mode.DELAY, 50);

        long start = System.nanoTime();
        GatewayResult result = gateway.approve(UUID.randomUUID(), 1L, 100L);

        assertThat(result.approved()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(45);
    }

    @Test
    void clientTimeout_pgKeepsProcessing_andInquiryShowsLateApproval() {
        FakePaymentGateway gateway = new FakePaymentGateway(dataSource, Mode.DELAY, 600, Duration.ofMillis(100));
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> gateway.approve(orderId, 1L, 100L))
                .isInstanceOf(PaymentGatewayTimeoutException.class);
        // PG 는 아직 처리 중 (원장에 없음)
        assertThat(gateway.inquire(orderId)).isEmpty();

        await().atMost(Duration.ofSeconds(5)).until(() -> gateway.inquire(orderId).isPresent());
        assertThat(gateway.inquire(orderId).orElseThrow().approved()).isTrue();
        // 같은 주문 재요청은 늦게 확정된 그 결과 (두 번 승인되지 않음)
        assertThat(gateway.approve(orderId, 1L, 100L)).isEqualTo(gateway.inquire(orderId).orElseThrow());
    }

    @Test
    void refund_marksApprovedTransactionOnce_andIgnoresUnknownOrder() {
        FakePaymentGateway gateway = new FakePaymentGateway(dataSource, Mode.APPROVE, 0);
        UUID orderId = UUID.randomUUID();
        gateway.approve(orderId, 1L, 100L);

        gateway.refund(orderId);
        gateway.refund(orderId);
        gateway.refund(UUID.randomUUID());

        assertThat(JdbcClient.create(dataSource)
                .sql("select refunded from fake_pg_transactions where order_id = ?")
                .param(orderId)
                .query(Boolean.class)
                .single()).isTrue();
    }
}
