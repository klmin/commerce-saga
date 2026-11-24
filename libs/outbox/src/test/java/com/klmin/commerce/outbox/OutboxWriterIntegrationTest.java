package com.klmin.commerce.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Debezium relay 모드(넣고 바로 지우기)에서도 INSERT 가 WAL 에 남아 커넥터가 읽을 수 있는지,
 * 롤백되면 아무것도 남지 않는지를 logical decoding(test_decoding) 으로 직접 확인한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxWriterIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withCommand("postgres", "-c", "wal_level=logical");

    private static final String SLOT = "outbox_writer_test";
    private static final String PUBLICATION = "outbox_writer_test_pub";

    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private final JsonMapper json = new JsonMapper();

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("""
                create table if not exists outbox (
                    id            uuid primary key,
                    aggregatetype varchar(100) not null,
                    aggregateid   varchar(100) not null,
                    type          varchar(100) not null,
                    payload       jsonb        not null,
                    created_at    timestamptz  not null default now(),
                    traceparent   varchar(55)
                )""").update();
        jdbc.sql("truncate outbox").update();
        jdbc.sql("select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = ?")
                .param(SLOT).query().listOfRows();
        jdbc.sql("select pg_create_logical_replication_slot(?, 'test_decoding')").param(SLOT).query().listOfRows();
        // Debezium 커넥터가 만드는 것처럼 outbox 를 publication 에 넣는다 (Debezium 모드 쓰기의 전제)
        jdbc.sql("drop publication if exists " + PUBLICATION).update();
        jdbc.sql("create publication " + PUBLICATION + " for table outbox").update();
    }

    @Test
    void debezium_모드는_slot_이나_publication_이_없으면_쓰지_않고_롤백한다() {
        var writer = new OutboxWriter(jdbc, json, true);

        jdbc.sql("drop publication " + PUBLICATION).update();
        assertThatThrownBy(() -> tx.execute(status -> writer.append("inventory.command", "order-1", "ReserveStock",
                Map.of("a", 1)))).isInstanceOf(OutboxNotReadyException.class);
        jdbc.sql("create publication " + PUBLICATION + " for table outbox").update();
        jdbc.sql("select pg_drop_replication_slot(?)").param(SLOT).query().listOfRows();
        assertThatThrownBy(() -> tx.execute(status -> writer.append("inventory.command", "order-2", "ReserveStock",
                Map.of("a", 1)))).isInstanceOf(OutboxNotReadyException.class);
        assertThat(outboxCount()).isZero();

        // 커넥터가 slot 을 만들면 바로 풀린다
        jdbc.sql("select pg_create_logical_replication_slot(?, 'test_decoding')").param(SLOT).query().listOfRows();
        UUID id = tx.execute(status -> writer.append("inventory.command", "order-3", "ReserveStock", Map.of("a", 1)));
        assertThat(walChanges()).anySatisfy(c -> assertThat(c).startsWith("table public.outbox: INSERT:")
                .contains(id.toString()));
    }

    @Test
    void 준비_확인은_된_뒤에만_잠깐_재사용하고_slot_이_사라지면_다시_막힌다() {
        var clock = new MutableClock(Instant.parse("2026-11-24T00:00:00Z"));
        var readiness = new DebeziumRelayReadiness(jdbc, clock, Duration.ofSeconds(5));

        assertThat(readiness.ready()).isTrue();
        jdbc.sql("select pg_drop_replication_slot(?)").param(SLOT).query().listOfRows();
        assertThat(readiness.ready()).isTrue(); // 5초 안에는 조회하지 않는다
        clock.advance(Duration.ofSeconds(5));
        assertThat(readiness.ready()).isFalse();
        // 준비 전에는 매번 조회해 slot 이 생기면 바로 true
        jdbc.sql("select pg_create_logical_replication_slot(?, 'test_decoding')").param(SLOT).query().listOfRows();
        assertThat(readiness.ready()).isTrue();
        assertThat(RelayReadiness.ALWAYS.ready()).isTrue();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void debezium_모드는_커밋후_테이블은_비지만_WAL_에는_INSERT_가_남는다() {
        var writer = new OutboxWriter(jdbc, json, true);

        UUID id = tx.execute(status -> writer.append("inventory.command", "order-1", "ReserveStock", Map.of("a", 1)));

        assertThat(outboxCount()).isZero();
        List<String> changes = walChanges();
        assertThat(changes).anySatisfy(c -> assertThat(c).startsWith("table public.outbox: INSERT:").contains(id.toString())
                .contains("aggregatetype[character varying]:'inventory.command'")
                .contains("type[character varying]:'ReserveStock'"));
        assertThat(changes).anySatisfy(c -> assertThat(c).startsWith("table public.outbox: DELETE:").contains(id.toString()));
    }

    @Test
    void debezium_모드에서_롤백되면_WAL_에_outbox_변경이_없다() {
        var writer = new OutboxWriter(jdbc, json, true);

        tx.executeWithoutResult(status -> {
            writer.append("inventory.command", "order-1", "ReserveStock", Map.of("a", 1));
            status.setRollbackOnly();
        });

        assertThat(outboxCount()).isZero();
        assertThat(walChanges()).noneMatch(c -> c.startsWith("table public.outbox"));
    }

    @Test
    void 스케줄러_모드는_행을_남겨_relay_가_읽게_한다() {
        var writer = new OutboxWriter(jdbc, json);

        UUID id = tx.execute(status -> writer.append("inventory.command", "order-1", "ReserveStock", Map.of("a", 1)));

        assertThat(id.version()).isEqualTo(7);
        assertThat(jdbc.sql("select count(*) from outbox where id = ?").param(id).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void 넣는_시점의_traceparent_를_저장하고_Debezium_도_WAL_에서_읽을_수_있다() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        var scheduler = new OutboxWriter(jdbc, json, false, () -> traceparent);
        var debezium = new OutboxWriter(jdbc, json, true, () -> traceparent);
        var noTrace = new OutboxWriter(jdbc, json, false, OutboxTraceContext.NONE);

        UUID id = tx.execute(status -> scheduler.append("inventory.command", "order-1", "ReserveStock", Map.of("a", 1)));
        UUID cdcId = tx.execute(status -> debezium.append("inventory.command", "order-2", "ReserveStock", Map.of("a", 1)));
        UUID plainId = tx.execute(status -> noTrace.append("inventory.command", "order-3", "ReserveStock", Map.of("a", 1)));

        assertThat(traceparentOf(id)).isEqualTo(traceparent);
        assertThat(traceparentOf(plainId)).isNull();
        assertThat(walChanges()).anySatisfy(c -> assertThat(c).startsWith("table public.outbox: INSERT:")
                .contains(cdcId.toString())
                .contains("traceparent[character varying]:'" + traceparent + "'"));
    }

    @Test
    void 메트릭은_미발행_행과_slot_이_붙잡은_WAL_을_보여준다() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        new OutboxMetrics(jdbc, java.time.Clock.systemUTC(), java.time.Duration.ZERO).bindTo(registry);
        var scheduler = new OutboxWriter(jdbc, json);

        tx.executeWithoutResult(status -> {
            scheduler.append("inventory.command", "order-1", "ReserveStock", Map.of("a", 1));
            scheduler.append("inventory.command", "order-2", "ReserveStock", Map.of("a", 1));
        });

        assertThat(registry.get("commerce.outbox.pending.rows").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("commerce.outbox.oldest.pending.age").timeGauge().value()).isGreaterThanOrEqualTo(0.0);
        // setUp 의 test_decoding slot 은 아무도 읽지 않으므로 방금 쓴 WAL 을 붙잡고 있다
        assertThat(registry.get("commerce.outbox.replication.slot.lag").gauge().value()).isPositive();
    }

    private String traceparentOf(UUID id) {
        return jdbc.sql("select traceparent from outbox where id = ?").param(id).query(String.class).list().getFirst();
    }

    private long outboxCount() {
        return jdbc.sql("select count(*) from outbox").query(Long.class).single();
    }

    private List<String> walChanges() {
        return jdbc.sql("select data from pg_logical_slot_get_changes(?, null, null)")
                .param(SLOT)
                .query(String.class)
                .list();
    }
}
