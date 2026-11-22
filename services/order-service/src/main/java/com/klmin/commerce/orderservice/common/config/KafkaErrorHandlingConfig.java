package com.klmin.commerce.orderservice.common.config;

import com.klmin.commerce.orderservice.common.error.NonRetryableMessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * 리스너 오류 처리. Spring Kafka 기본값(10회 재시도 후 로그만 남기고 건너뜀)이면
 * DB 장애가 길어질 때 정상 메시지가 유실되므로, 일시 오류는 복구될 때까지 재시도한다
 * (0.5초부터 2배씩, 최대 30초 간격, 횟수 무제한. 그동안 그 파티션은 멈춘다).
 * <p>
 * 재시도해도 결과가 같은 오류(본문 JSON 파싱 실패, 본문이 비었거나 필수 값 누락 등)는
 * ERROR 로그를 남기고 건너뛴다. 이런 메시지가 파티션을 무한히 막지 않게 하기 위해서다.
 * 이런 메시지를 보존·재처리하는 DLQ 는 3단계에서 추가한다.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxInterval(30_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> log.error(
                "처리할 수 없는 메시지 건너뜀 (topic={}, partition={}, offset={}, key={})",
                record.topic(), record.partition(), record.offset(), record.key(), ex), backOff);
        handler.addNotRetryableExceptions(JacksonException.class, NonRetryableMessageException.class);
        return handler;
    }
}
