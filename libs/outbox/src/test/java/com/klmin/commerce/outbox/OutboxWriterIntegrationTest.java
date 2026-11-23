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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
                    created_at    timestamptz  not null default now()
                )""").update();
        jdbc.sql("truncate outbox").update();
        jdbc.sql("select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = ?")
                .param(SLOT).query().listOfRows();
        jdbc.sql("select pg_create_logical_replication_slot(?, 'test_decoding')").param(SLOT).query().listOfRows();
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
