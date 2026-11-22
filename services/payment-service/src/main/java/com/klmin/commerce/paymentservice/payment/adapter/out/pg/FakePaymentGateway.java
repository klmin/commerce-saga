package com.klmin.commerce.paymentservice.payment.adapter.out.pg;

import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Mode;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayFaultPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 가짜 PG. 실제 PG 처럼 orderId 를 멱등 키로 다뤄 같은 주문의 재요청에는 처음 결과를 돌려준다(이중 승인 없음).
 * <p>
 * PG 쪽 거래 원장(fake_pg_transactions)은 결제 서비스 트랜잭션과 따로, 승인 즉시 커밋한다
 * (외부 PG 의 기록을 흉내 낸 것). 그래서 PG 승인 후 결과 기록 전에 프로세스가 죽고 재시작해도
 * 재처리 때 원장의 처음 결과를 돌려준다. 장애 주입 모드(승인·거절·지연)는 실행 중에 바꿀 수 있다.
 */
@Component
class FakePaymentGateway implements PaymentGatewayPort, PaymentGatewayFaultPort {

    private static final String INSERT = """
            insert into fake_pg_transactions (order_id, user_id, amount, approved, transaction_id, reason)
            values (?, ?, ?, ?, ?, ?)
            on conflict (order_id) do nothing
            """;

    private static final String SELECT = """
            select approved, transaction_id, reason from fake_pg_transactions where order_id = ?
            """;

    private final AtomicReference<Settings> settings;
    // 트랜잭션에 참여하지 않는 별도 JdbcClient. 호출 시점에 바로 커밋된다 (PG 는 우리 트랜잭션을 모른다)
    private final JdbcClient ledger;

    FakePaymentGateway(DataSource dataSource,
                       @Value("${fake-pg.mode:APPROVE}") Mode mode,
                       @Value("${fake-pg.delay-millis:0}") long delayMillis) {
        this.ledger = JdbcClient.create(dataSource);
        this.settings = new AtomicReference<>(new Settings(mode, delayMillis));
    }

    @Override
    public GatewayResult approve(UUID orderId, long userId, long amount) {
        GatewayResult previous = find(orderId);
        if (previous != null) {
            return previous;
        }
        GatewayResult decided = decide(settings.get());
        // 동시에 같은 주문이 들어오면 먼저 들어간 결과가 이긴다. 진 쪽도 이긴 결과를 돌려준다
        ledger.sql(INSERT)
                .params(orderId, userId, amount, decided.approved(), decided.transactionId(), decided.reason())
                .update();
        return find(orderId);
    }

    private GatewayResult find(UUID orderId) {
        return ledger.sql(SELECT)
                .param(orderId)
                .query((rs, rowNum) -> new GatewayResult(rs.getBoolean("approved"), rs.getString("transaction_id"),
                        rs.getString("reason")))
                .optional()
                .orElse(null);
    }

    private static GatewayResult decide(Settings current) {
        return switch (current.mode()) {
            case APPROVE -> GatewayResult.approved(transactionId());
            case DECLINE -> GatewayResult.declined("declined by PG (fault injection)");
            case DELAY -> {
                sleep(current.delayMillis());
                yield GatewayResult.approved(transactionId());
            }
        };
    }

    private static String transactionId() {
        return "fake-" + UUID.randomUUID();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PG 응답 대기 중 인터럽트", e);
        }
    }

    @Override
    public Settings current() {
        return settings.get();
    }

    @Override
    public void change(Settings newSettings) {
        settings.set(newSettings);
    }
}
