package com.klmin.commerce.outbox;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.micrometer.KafkaListenerObservationConvention;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"})
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(DataSource dataSource, ObjectProvider<JsonMapper> jsonMapper, OutboxRelayProperties props,
                              ObjectProvider<OutboxTraceContext> traceContext) {
        // 스케줄러 relay 를 끄면 Debezium 이 WAL 로 발행하므로 넣고 바로 지운다
        return new OutboxWriter(JdbcClient.create(dataSource), jsonMapper.getIfAvailable(JsonMapper::new),
                !props.enabled(), traceContext.getIfAvailable(() -> OutboxTraceContext.NONE));
    }

    /** Micrometer 가 있으면 outbox 적체·slot WAL 적체를 메트릭으로 내보낸다 (/actuator/prometheus) */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        OutboxMetrics outboxMetrics(DataSource dataSource, ObjectProvider<Clock> clock) {
            return new OutboxMetrics(JdbcClient.create(dataSource), clock.getIfAvailable(Clock::systemUTC),
                    Duration.ofSeconds(2));
        }
    }

    /** Micrometer Tracing 이 있으면 outbox 에 넣는 시점의 traceparent 를 저장한다 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class TracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        OutboxTraceContext outboxTraceContext(ObjectProvider<io.micrometer.tracing.Tracer> tracer,
                                              ObjectProvider<io.micrometer.tracing.propagation.Propagator> propagator) {
            // Tracer/Propagator 빈은 tracing 자동 설정이 만든다. 순서에 기대지 않게 쓸 때 꺼낸다
            return new MicrometerOutboxTraceContext(tracer, propagator);
        }

        /** 리스너 span 에 orderId(메시지 키)·메시지 타입을 단다. Boot 가 리스너 컨테이너 팩토리에 넣는다 */
        @Bean
        @ConditionalOnMissingBean(KafkaListenerObservationConvention.class)
        KafkaListenerObservationConvention messageTracingConvention() {
            return new MessageTracingConvention();
        }
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
