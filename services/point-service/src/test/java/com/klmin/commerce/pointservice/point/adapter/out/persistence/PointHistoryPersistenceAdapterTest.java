package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import com.github.f4b6a3.uuid.UuidCreator;
import com.klmin.commerce.pointservice.point.application.port.in.PointHistoryEntryResult;
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

/** 내 포인트 내역: 주문 사용 + 지급을 한 목록으로 (최신순 커서 페이징, 본인 것만) */
@Testcontainers(disabledWithoutDocker = true)
class PointHistoryPersistenceAdapterTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    private JdbcClient jdbc;
    private PointHistoryPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load()
                .clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        adapter = new PointHistoryPersistenceAdapter(jdbc);
    }

    @Test
    void usagesAndGrants_newestFirst_withCursor_onlyMine() {
        UUID bonus = grant(1L, 1_000L);
        UUID used = usage(1L, 300L, "USED", null);
        UUID refunded = usage(1L, 200L, "REFUNDED", "order cancelled");
        UUID rejected = usage(1L, 5_000L, "REJECTED", "insufficient points");
        usage(2L, 100L, "USED", null);
        grant(2L, 1_000L);
        refundedBeforeUsage(); // 사용자를 모르는 기록은 누구의 내역에도 없다

        List<PointHistoryEntryResult> page1 = adapter.findByUser(1L, null, 2);
        List<PointHistoryEntryResult> page2 = adapter.findByUser(1L, page1.getLast().entryId(), 2);
        List<PointHistoryEntryResult> page3 = adapter.findByUser(1L, page2.getLast().entryId(), 2);

        assertThat(page1).extracting(PointHistoryEntryResult::entryId).containsExactly(rejected, refunded);
        assertThat(page2).extracting(PointHistoryEntryResult::entryId).containsExactly(used, bonus);
        assertThat(page3).isEmpty();

        PointHistoryEntryResult refundedEntry = page1.get(1);
        assertThat(refundedEntry.kind()).isEqualTo("ORDER");
        assertThat(refundedEntry.orderId()).isEqualTo(refunded);
        assertThat(refundedEntry.amount()).isEqualTo(200L);
        assertThat(refundedEntry.status()).isEqualTo("REFUNDED");
        assertThat(refundedEntry.reason()).isEqualTo("order cancelled");

        PointHistoryEntryResult bonusEntry = page2.get(1);
        assertThat(bonusEntry.kind()).isEqualTo("SIGNUP_BONUS");
        assertThat(bonusEntry.orderId()).isNull();
        assertThat(bonusEntry.amount()).isEqualTo(1_000L);
        assertThat(bonusEntry.status()).isEqualTo("GRANTED");
        assertThat(bonusEntry.createdAt()).isEqualTo(NOW);

        assertThat(adapter.findByUser(3L, null, 20)).isEmpty();
        assertThat(adapter.findByUser(1L, null, 50)).hasSize(4);
    }

    @Test
    void manyGrantsOrUsages_onOneSide_stillPageBySize() {
        for (int i = 0; i < 3; i++) {
            usage(1L, 10L, "USED", null);
        }
        UUID between = UuidCreator.getTimeOrderedEpoch(); // 어느 표에도 없는 커서여도 그보다 오래된 것만

        UUID newestGrant = grant(1L, 500L);

        List<PointHistoryEntryResult> page = adapter.findByUser(1L, null, 2);
        assertThat(page).hasSize(2);
        assertThat(page.getFirst().entryId()).isEqualTo(newestGrant);
        assertThat(adapter.findByUser(1L, between, 20)).hasSize(3)
                .allSatisfy(e -> assertThat(e.kind()).isEqualTo("ORDER"));
    }

    private UUID usage(long userId, long amount, String status, String reason) {
        UUID orderId = UuidCreator.getTimeOrderedEpoch();
        jdbc.sql("""
                        insert into point_usages (order_id, user_id, amount, status, reason, created_at, updated_at,
                                                  version)
                        values (?, ?, ?, ?, ?, ?, ?, 0)""")
                .params(orderId, userId, amount, status, reason, Timestamp.from(NOW), Timestamp.from(NOW))
                .update();
        return orderId;
    }

    private void refundedBeforeUsage() {
        jdbc.sql("""
                        insert into point_usages (order_id, user_id, amount, status, reason, created_at, updated_at,
                                                  version)
                        values (?, null, 0, 'REFUNDED', 'refunded before usage', ?, ?, 0)""")
                .params(UuidCreator.getTimeOrderedEpoch(), Timestamp.from(NOW), Timestamp.from(NOW))
                .update();
    }

    private UUID grant(long userId, long amount) {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        jdbc.sql("insert into point_grants (id, user_id, amount, type, created_at) values (?, ?, ?, 'SIGNUP_BONUS', ?)")
                .params(id, userId, amount, Timestamp.from(NOW))
                .update();
        return id;
    }
}
