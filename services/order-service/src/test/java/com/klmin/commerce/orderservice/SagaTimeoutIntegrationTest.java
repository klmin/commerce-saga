package com.klmin.commerce.orderservice;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReleaseStock;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.contract.inventory.StockReleased;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.contract.order.OrderCancelled;
import com.klmin.commerce.contract.payment.Pay;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderSagaUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import com.klmin.commerce.orderservice.order.domain.OrderStatus;
import com.klmin.commerce.orderservice.order.domain.SagaEventType;
import com.klmin.commerce.orderservice.order.domain.SagaHistoryEntry;
import com.klmin.commerce.orderservice.order.domain.SagaState;
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
 * 멈춘 Saga 정리 통합 테스트 (ADR 0007). 단계 타임아웃을 짧게 두고, 참여 서비스 역할의 테스트가 응답하지 않을 때
 * 커맨드가 다시 나가는지, 끝내 응답이 없으면 역순 보상하는지를 실제 DB·Kafka 로 확인한다.
 */
@SpringBootTest(properties = {
        "saga.timeout.step-timeout=2s",
        "saga.timeout.max-resends=1",
        "saga.timeout.scan-interval=500ms"
})
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SagaTimeoutIntegrationTest {

    @Autowired
    PlaceOrderUseCase placeOrderUseCase;

    @Autowired
    GetOrderUseCase getOrderUseCase;

    @Autowired
    GetOrderSagaUseCase getOrderSagaUseCase;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    // 테스트에서 참여 서비스 역할로 응답을 보낼 때만 쓴다
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper json;

    private Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        consumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), "test");
        consumer.subscribe(List.of(Topics.INVENTORY_COMMAND, Topics.PAYMENT_COMMAND, Topics.ORDER_EVENTS));
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    private Order place(long userId) {
        return placeOrderUseCase.placeOrder(new PlaceOrderCommand(userId, List.of(
                new PlaceOrderCommand.Line(10L, 1, 1_000L)), 0L)).order();
    }

    @Test
    void noReply_resendsReserveStock_thenReleasesStock_andCancelsOrder() {
        Order order = place(21L);

        // 처음 + 재전송 1번. 재전송은 새 메시지 id 라 참여 서비스가 처리 기록으로 건너뛰지 않는다
        awaitCount(order, ReserveStock.TYPE, 2);
        assertThat(messageIds(order, ReserveStock.TYPE)).doesNotHaveDuplicates();

        // 그래도 응답이 없으면 예약됐다고 보고 해제한다
        awaitCount(order, ReleaseStock.TYPE, 1);
        reply(order, Topics.INVENTORY_REPLY, StockReleased.TYPE, new StockReleased(order.id()));

        awaitCount(order, OrderCancelled.TYPE, 1);
        awaitStatus(order, OrderStatus.CANCELLED);
        OrderSaga saga = getOrderSagaUseCase.getSaga(new GetOrderQuery(order.id(), order.userId()));
        assertThat(saga.history()).extracting(SagaHistoryEntry::event).containsExactly(
                OrderSaga.STARTED, OrderSaga.TIMEOUT_RESEND, OrderSaga.TIMEOUT_GIVE_UP,
                SagaEventType.STOCK_RELEASED.name());
        assertThat(saga.history()).extracting(SagaHistoryEntry::state).containsExactly(
                SagaState.RESERVING_STOCK, SagaState.RESERVING_STOCK, SagaState.RELEASING_STOCK, SagaState.CANCELLED);
        assertThat(getOrderUseCase.getOrder(new GetOrderQuery(order.id(), order.userId())).cancelReason())
                .isEqualTo("timeout: no reply in RESERVING_STOCK after 1 resends");
    }

    @Test
    void replyToResentCommand_continuesSaga() {
        Order order = place(22L);
        awaitCount(order, ReserveStock.TYPE, 2);

        // 참여 서비스가 복구되어 재전송된 커맨드에 응답했다
        reply(order, Topics.INVENTORY_REPLY, StockReserved.TYPE, new StockReserved(order.id()));

        awaitCount(order, Pay.TYPE, 1);
        assertThat(getOrderSagaUseCase.getSaga(new GetOrderQuery(order.id(), order.userId())).state())
                .isEqualTo(SagaState.PAYING);
        assertThat(count(order, ReleaseStock.TYPE)).isZero();
    }

    private void awaitCount(Order order, String type, int expected) {
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
            return count(order, type) >= expected;
        });
    }

    private long count(Order order, String type) {
        return records(order, type).size();
    }

    private List<String> messageIds(Order order, String type) {
        return records(order, type).stream().map(r -> header(r, MessageHeaders.ID)).toList();
    }

    private List<ConsumerRecord<String, String>> records(Order order, String type) {
        return received.stream()
                .filter(r -> order.id().toString().equals(r.key()) && type.equals(header(r, MessageHeaders.TYPE)))
                .toList();
    }

    private void reply(Order order, String topic, String type, Object payload) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, order.id().toString(), json.writeValueAsString(payload));
        record.headers().add(MessageHeaders.ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private void awaitStatus(Order order, OrderStatus expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(getOrderUseCase.getOrder(new GetOrderQuery(order.id(), order.userId())).status())
                        .isEqualTo(expected));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
