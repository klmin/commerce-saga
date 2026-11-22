package com.klmin.commerce.pointservice;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.point.PointRefunded;
import com.klmin.commerce.contract.point.PointUseFailed;
import com.klmin.commerce.contract.point.PointUsed;
import com.klmin.commerce.contract.point.RefundPoint;
import com.klmin.commerce.contract.point.UsePoint;
import com.klmin.commerce.pointservice.point.application.port.in.GetPointBalanceUseCase;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * point.command(UsePoint, RefundPoint) -> 포인트 차감·환불 -> outbox -> relay -> point.reply.
 * order-service 대신 테스트가 커맨드를 보낸다. 계정은 V2 시드(사용자 1~3: 10,000P, 4: 0P).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PointIntegrationTest {

    @Autowired
    GetPointBalanceUseCase getPointBalanceUseCase;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    // 테스트에서 order-service 역할로 커맨드를 보낼 때만 쓴다
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper json;

    private Consumer<String, String> replyConsumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        replyConsumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), "test");
        replyConsumer.subscribe(List.of(Topics.POINT_REPLY));
    }

    @AfterEach
    void close() {
        replyConsumer.close();
    }

    @Test
    void use_thenRefund_twice_returnsPointsOnce() {
        long before = getPointBalanceUseCase.getBalance(1L).balance();
        UUID orderId = UUID.randomUUID();

        send(orderId, UsePoint.TYPE, new UsePoint(orderId, 1L, 1_500L));
        assertThat(type(awaitReplies(orderId, 1).getFirst())).isEqualTo(PointUsed.TYPE);
        assertThat(getPointBalanceUseCase.getBalance(1L).balance()).isEqualTo(before - 1_500L);

        send(orderId, RefundPoint.TYPE, new RefundPoint(orderId));
        send(orderId, RefundPoint.TYPE, new RefundPoint(orderId));
        List<ConsumerRecord<String, String>> replies = awaitReplies(orderId, 3);
        assertThat(replies.subList(1, 3)).allMatch(r -> PointRefunded.TYPE.equals(type(r)));
        assertThat(getPointBalanceUseCase.getBalance(1L).balance()).isEqualTo(before);
    }

    @Test
    void insufficientPoints_repliesFailed() {
        UUID orderId = UUID.randomUUID();

        send(orderId, UsePoint.TYPE, new UsePoint(orderId, 4L, 100L));

        ConsumerRecord<String, String> reply = awaitReplies(orderId, 1).getFirst();
        assertThat(type(reply)).isEqualTo(PointUseFailed.TYPE);
        assertThat(json.readValue(reply.value(), PointUseFailed.class).reason()).isEqualTo("insufficient points: userId=4");
        assertThat(getPointBalanceUseCase.getBalance(4L).balance()).isZero();
    }

    private void send(UUID orderId, String type, Object payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.POINT_COMMAND, orderId.toString(),
                json.writeValueAsString(payload));
        record.headers().add(MessageHeaders.ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private List<ConsumerRecord<String, String>> awaitReplies(UUID orderId, int count) {
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            replyConsumer.poll(Duration.ofMillis(200)).forEach(received::add);
            return received.stream().filter(r -> orderId.toString().equals(r.key())).count() >= count;
        });
        return received.stream().filter(r -> orderId.toString().equals(r.key())).toList();
    }

    private static String type(ConsumerRecord<?, ?> record) {
        var header = record.headers().lastHeader(MessageHeaders.TYPE);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
