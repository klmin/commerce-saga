package com.klmin.commerce.paymentservice.payment.adapter.out.pg;

import com.github.f4b6a3.uuid.UuidCreator;
import com.klmin.commerce.paymentservice.payment.application.PaymentGatewayTimeoutException;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Mode;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayFaultPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 가짜 PG. 실제 PG 처럼 orderId 를 멱등 키로 다뤄 같은 주문의 재요청에는 처음 결과를 돌려준다(이중 승인 없음).
 * <p>
 * PG 쪽 거래 원장(fake_pg_transactions)은 결제 서비스 트랜잭션과 따로, 승인 즉시 커밋한다
 * (외부 PG 의 기록을 흉내 낸 것). 그래서 PG 승인 후 결과 기록 전에 프로세스가 죽고 재시작해도
 * 재처리 때 원장의 처음 결과를 돌려준다. 장애 주입 모드(승인·거절·지연)는 실행 중에 바꿀 수 있다.
 * <p>
 * 승인 처리는 "PG 쪽"(별도 스레드)에서 돈다. 호출한 쪽은 {@code client-timeout} 까지만 기다리고
 * 넘으면 {@link PaymentGatewayTimeoutException} 을 받지만, PG 는 계속 처리해 나중에 승인한다(DELAY 모드).
 * 결과는 {@link #inquire} 로 확인한다. 실제 PG 처럼 이 서비스가 재시작되면 처리 중이던 요청은 사라진다(원장에 없음).
 */
@Component
class FakePaymentGateway implements PaymentGatewayPort, PaymentGatewayFaultPort, DisposableBean {

    private static final String INSERT = """
            insert into fake_pg_transactions (order_id, user_id, amount, approved, transaction_id, reason)
            values (?, ?, ?, ?, ?, ?)
            on conflict (order_id) do nothing
            """;

    private static final String SELECT = """
            select approved, transaction_id, reason from fake_pg_transactions where order_id = ?
            """;

    private static final String REFUND = """
            update fake_pg_transactions set refunded = true where order_id = ? and approved and not refunded
            """;

    private final AtomicReference<Settings> settings;
    // 트랜잭션에 참여하지 않는 별도 JdbcClient. 호출 시점에 바로 커밋된다 (PG 는 우리 트랜잭션을 모른다)
    private final JdbcClient ledger;
    private final Duration clientTimeout;
    // PG 쪽에서 처리 중인 승인 요청 (같은 주문의 재요청은 같은 처리를 기다린다)
    private final Map<UUID, CompletableFuture<GatewayResult>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService pg = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    FakePaymentGateway(DataSource dataSource,
                       @Value("${fake-pg.mode:APPROVE}") Mode mode,
                       @Value("${fake-pg.delay-millis:0}") long delayMillis,
                       @Value("${fake-pg.client-timeout:3s}") Duration clientTimeout) {
        this.ledger = JdbcClient.create(dataSource);
        this.settings = new AtomicReference<>(new Settings(mode, delayMillis));
        this.clientTimeout = clientTimeout;
    }

    FakePaymentGateway(DataSource dataSource, Mode mode, long delayMillis) {
        this(dataSource, mode, delayMillis, Duration.ofSeconds(3));
    }

    @Override
    public GatewayResult approve(UUID orderId, long userId, long amount) {
        GatewayResult previous = find(orderId);
        if (previous != null) {
            return previous;
        }
        Settings current = settings.get();
        CompletableFuture<GatewayResult> processing = inFlight.computeIfAbsent(orderId, id ->
                CompletableFuture.supplyAsync(() -> process(id, userId, amount, current), pg));
        // 맵 등록이 끝난 뒤 연결한다. 등록 전에 끝난 작업이면 콜백이 computeIfAbsent 안에서 돌아
        // remove 가 실패하고 실패한 future 가 맵에 남는다. 조건부 remove 라 여러 번 연결돼도 안전하다
        processing.whenComplete((result, error) -> inFlight.remove(orderId, processing));
        try {
            return processing.get(clientTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new PaymentGatewayTimeoutException(orderId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PG 응답 대기 중 인터럽트", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("PG 처리 실패: orderId=" + orderId, e.getCause());
        }
    }

    // PG 쪽 처리: 결정(지연 포함) → 원장 기록. 동시에 같은 주문이 들어오면 먼저 들어간 결과가 이긴다
    private GatewayResult process(UUID orderId, long userId, long amount, Settings current) {
        GatewayResult decided = decide(current);
        ledger.sql(INSERT)
                .params(orderId, userId, amount, decided.approved(), decided.transactionId(), decided.reason())
                .update();
        return find(orderId);
    }

    @Override
    public Optional<GatewayResult> inquire(UUID orderId) {
        return Optional.ofNullable(find(orderId));
    }

    @Override
    public void refund(UUID orderId) {
        ledger.sql(REFUND).param(orderId).update();
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
        return "fake-" + UuidCreator.getTimeOrderedEpoch();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PG 처리 중 인터럽트", e);
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

    @Override
    public void destroy() {
        pg.shutdownNow();
    }
}
