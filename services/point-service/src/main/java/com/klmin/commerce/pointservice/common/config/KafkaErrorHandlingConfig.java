package com.klmin.commerce.pointservice.common.config;

import com.klmin.commerce.pointservice.common.error.InvariantViolationException;
import com.klmin.commerce.pointservice.common.error.NonRetryableMessageException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * 리스너 오류 처리.
 * <ul>
 *   <li>일시 오류(DB 장애 등): 복구될 때까지 재시도한다 (0.5초부터 2배씩, 최대 30초 간격, 횟수 무제한).
 *       Spring Kafka 기본값(10회 후 건너뜀)이면 장애가 길어질 때 정상 메시지가 유실된다</li>
 *   <li>재시도해도 결과가 같은 오류(JSON 파싱 실패, 본문 없음, 필수 값 누락): 원본 메시지를
 *       {@code <토픽>.DLT} 로 옮긴 뒤 다음 메시지로 넘어간다. 파티션을 무한히 막지 않으면서 원본은 보존한다.
 *       DLT 발행이 실패하면 오프셋을 커밋하지 않고 다시 시도한다</li>
 * </ul>
 * DLT 는 원본을 그대로 옮기는 인프라 처리라 outbox 를 거치지 않는다 (보낼 비즈니스 변경이 없다).
 * 지연 단계별 retry 토픽과 DLT 재처리 도구는 3단계에서 추가한다.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandlingConfig {

    static final String DLT_SUFFIX = ".DLT";

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // 파티션을 -1 로 두면 원본 키(orderId)로 파티션이 정해진다
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxInterval(30_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class, NonRetryableMessageException.class,
                InvariantViolationException.class);
        return handler;
    }
}
