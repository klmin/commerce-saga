package com.klmin.commerce.outbox;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;

@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"})
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(DataSource dataSource, ObjectProvider<JsonMapper> jsonMapper, OutboxRelayProperties props) {
        // 스케줄러 relay 를 끄면 Debezium 이 WAL 로 발행하므로 넣고 바로 지운다
        return new OutboxWriter(JdbcClient.create(dataSource), jsonMapper.getIfAvailable(JsonMapper::new),
                !props.enabled());
    }

    /** Debezium relay 모드: 스케줄러 대신 커넥터가 WAL 로 발행한다. slot 이 없으면 기동 때 경고 */
    @Bean
    @ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "false")
    DebeziumSlotCheck debeziumSlotCheck(DataSource dataSource) {
        return new DebeziumSlotCheck(JdbcClient.create(dataSource));
    }

    /** 스케줄러 relay. Debezium 으로 발행할 때는 outbox.relay.enabled=false */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulerRelayConfiguration implements SchedulingConfigurer {

        private final ObjectProvider<SchedulerOutboxRelay> relay;
        private final OutboxRelayProperties props;

        SchedulerRelayConfiguration(ObjectProvider<SchedulerOutboxRelay> relay, OutboxRelayProperties props) {
            this.relay = relay;
            this.props = props;
        }

        @Bean
        @ConditionalOnMissingBean
        SchedulerOutboxRelay schedulerOutboxRelay(DataSource dataSource, KafkaTemplate<String, String> kafka,
                                                  PlatformTransactionManager transactionManager) {
            return new SchedulerOutboxRelay(JdbcClient.create(dataSource), kafka,
                    new TransactionTemplate(transactionManager), props);
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(() -> relay.getObject().relayOnSchedule(), props.fixedDelay());
        }
    }
}
