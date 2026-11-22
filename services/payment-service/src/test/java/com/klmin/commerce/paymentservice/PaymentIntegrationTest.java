package com.klmin.commerce.paymentservice;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.payment.Pay;
import com.klmin.commerce.contract.payment.PaymentApproved;
import com.klmin.commerce.contract.payment.PaymentFailed;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Mode;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
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

/** payment.command(Pay) -> 가짜 PG -> 결과 기록 -> outbox -> relay -> payment.reply */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PaymentIntegrationTest {

    @Autowired
    GetPaymentUseCase getPaymentUseCase;

    @Autowired
    PaymentFaultUseCase paymentFaultUseCase;

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
        paymentFaultUseCase.change(new Settings(Mode.APPROVE, 0));
        replyConsumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), "test");
        replyConsumer.subscribe(List.of(Topics.PAYMENT_REPLY));
    }

    @AfterEach
    void close() {
        replyConsumer.close();
        paymentFaultUseCase.change(new Settings(Mode.APPROVE, 0));
    }

    @Test
    void pay_approved_andDuplicateRepliesSamePayment() {
        UUID orderId = UUID.randomUUID();

        send(new Pay(orderId, 1L, 5_000L));
        send(new Pay(orderId, 1L, 5_000L));

        List<ConsumerRecord<String, String>> replies = awaitReplies(orderId, 2);
        assertThat(replies).allMatch(r -> PaymentApproved.TYPE.equals(type(r)));
        UUID paymentId = getPaymentUseCase.getPayment(new GetPaymentQuery(orderId, 1L)).id();
        assertThat(replies).allMatch(r ->
                paymentId.equals(json.readValue(r.value(), PaymentApproved.class).paymentId()));
    }

    @Test
    void pay_declinedByFaultInjection_repliesFailed() {
        paymentFaultUseCase.change(new Settings(Mode.DECLINE, 0));
        UUID orderId = UUID.randomUUID();

        send(new Pay(orderId, 2L, 3_000L));

        ConsumerRecord<String, String> reply = awaitReplies(orderId, 1).getFirst();
        assertThat(type(reply)).isEqualTo(PaymentFailed.TYPE);
        assertThat(getPaymentUseCase.getPayment(new GetPaymentQuery(orderId, 2L)).status().name())
                .isEqualTo("DECLINED");
    }

    private void send(Pay command) {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.PAYMENT_COMMAND,
                command.orderId().toString(), json.writeValueAsString(command));
        record.headers().add(MessageHeaders.ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(MessageHeaders.TYPE, Pay.TYPE.getBytes(StandardCharsets.UTF_8));
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
