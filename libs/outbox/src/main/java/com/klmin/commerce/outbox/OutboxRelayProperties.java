package com.klmin.commerce.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 스케줄러 relay 설정 ({@code outbox.relay.*}).
 *
 * @param enabled     false 면 스케줄러 relay 를 끈다 (Debezium 으로 발행할 때)
 * @param fixedDelay  한 번 발행을 마친 뒤 다음 조회까지 쉬는 시간
 * @param batchSize   한 번에 가져와 발행할 최대 행 수
 * @param sendTimeout Kafka ack 를 기다리는 최대 시간
 */
@ConfigurationProperties("outbox.relay")
public record OutboxRelayProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("200ms") Duration fixedDelay,
        @DefaultValue("100") int batchSize,
        @DefaultValue("10s") Duration sendTimeout) {
}
