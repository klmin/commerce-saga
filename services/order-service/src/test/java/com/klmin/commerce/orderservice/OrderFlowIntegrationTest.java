package com.klmin.commerce.orderservice;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReleaseStock;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.contract.inventory.StockReleased;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.contract.order.OrderCancelled;
import com.klmin.commerce.contract.order.OrderCompleted;
import com.klmin.commerce.contract.payment.Pay;
import com.klmin.commerce.contract.payment.PaymentApproved;
import com.klmin.commerce.contract.payment.PaymentFailed;
import com.klmin.commerce.contract.point.PointRefunded;
import com.klmin.commerce.contract.point.PointUsed;
import com.klmin.commerce.contract.point.RefundPoint;
import com.klmin.commerce.contract.point.UsePoint;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderSagaUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderStatus;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 주문 Saga 오케스트레이션 통합 테스트. 참여 서비스(inventory, point, payment) 역할은 테스트가 한다:
 * 커맨드 토픽을 구독해 order-service 가 보낸 커맨드를 확인하고 응답을 직접 보낸다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class OrderFlowIntegrationTest {

    @Autowired
    PlaceOrderUseCase placeOrderUseCase;

    @Autowired
    GetOrderUseCase getOrderUseCase;

    @Autowired
    GetOrderSagaUseCase getOrderSagaUseCase;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    // 테스트에서 참여 서비스 역할로 응답을 보낼 때만 쓴다 (서비스 코드는 outbox 만 사용)
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper json;

    @Autowired
    DataSource dataSource;

    private Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        consumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), "test");
        consumer.subscribe(List.of(Topics.INVENTORY_COMMAND, Topics.POINT_COMMAND, Topics.PAYMENT_COMMAND,
                Topics.ORDER_EVENTS));
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    /** 합계 2,000 */
    private Order place(long userId, long pointAmount) {
        return placeOrderUseCase.placeOrder(new PlaceOrderCommand(userId, List.of(
                new PlaceOrderCommand.Line(10L, 2, 1_000L)), pointAmount)).order();
    }

    @Test
    void happyPath_reserveStock_usePoint_pay_thenOrderCompletedEvent() {
        Order order = place(1L, 500L);

        ReserveStock reserve = expect(order, Topics.INVENTORY_COMMAND, ReserveStock.TYPE, ReserveStock.class);
        assertThat(reserve.lines()).containsExactly(new ReserveStock.Line(10L, 2));
        reply(order, Topics.INVENTORY_REPLY, StockReserved.TYPE, new StockReserved(order.id()));

        UsePoint usePoint = expect(order, Topics.POINT_COMMAND, UsePoint.TYPE, UsePoint.class);
        assertThat(usePoint.amount()).isEqualTo(500L);
        assertThat(usePoint.userId()).isEqualTo(1L);
        reply(order, Topics.POINT_REPLY, PointUsed.TYPE, new PointUsed(order.id()));

        Pay pay = expect(order, Topics.PAYMENT_COMMAND, Pay.TYPE, Pay.class);
        assertThat(pay.amount()).isEqualTo(1_500L);
        reply(order, Topics.PAYMENT_REPLY, PaymentApproved.TYPE, new PaymentApproved(order.id(), UUID.randomUUID()));

        OrderCompleted completed = expect(order, Topics.ORDER_EVENTS, OrderCompleted.TYPE, OrderCompleted.class);
        assertThat(completed.paidAmount()).isEqualTo(1_500L);
        assertThat(completed.pointAmount()).isEqualTo(500L);
        awaitStatus(order, OrderStatus.COMPLETED);
        assertThat(getOrderSagaUseCase.getSaga(new GetOrderQuery(order.id(), order.userId())).state()).isEqualTo(SagaState.COMPLETED);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(outboxCount(order.id())).isZero());
    }

    @Test
    void paymentFailed_refundsPointThenReleasesStock_thenOrderCancelledEvent() {
        Order order = place(2L, 500L);
        expect(order, Topics.INVENTORY_COMMAND, ReserveStock.TYPE, ReserveStock.class);
        reply(order, Topics.INVENTORY_REPLY, StockReserved.TYPE, new StockReserved(order.id()));
        expect(order, Topics.POINT_COMMAND, UsePoint.TYPE, UsePoint.class);
        reply(order, Topics.POINT_REPLY, PointUsed.TYPE, new PointUsed(order.id()));
        expect(order, Topics.PAYMENT_COMMAND, Pay.TYPE, Pay.class);

        reply(order, Topics.PAYMENT_REPLY, PaymentFailed.TYPE, new PaymentFailed(order.id(), "card declined"));

        // 보상은 정방향의 역순: 포인트 환불 -> 재고 해제
        expect(order, Topics.POINT_COMMAND, RefundPoint.TYPE, RefundPoint.class);
        reply(order, Topics.POINT_REPLY, PointRefunded.TYPE, new PointRefunded(order.id()));
        expect(order, Topics.INVENTORY_COMMAND, ReleaseStock.TYPE, ReleaseStock.class);
        reply(order, Topics.INVENTORY_REPLY, StockReleased.TYPE, new StockReleased(order.id()));

        OrderCancelled cancelled = expect(order, Topics.ORDER_EVENTS, OrderCancelled.TYPE, OrderCancelled.class);
        assertThat(cancelled.reason()).isEqualTo("card declined");
        awaitStatus(order, OrderStatus.CANCELLED);
        assertThat(getOrderSagaUseCase.getSaga(new GetOrderQuery(order.id(), order.userId())).history()).extracting(SagaHistoryEntry::state)
                .containsExactly(SagaState.RESERVING_STOCK, SagaState.USING_POINT, SagaState.PAYING,
                        SagaState.REFUNDING_POINT, SagaState.RELEASING_STOCK, SagaState.CANCELLED);
    }

    @Test
    void duplicateReply_doesNotSendNextCommandTwice() {
        Order order = place(3L, 0L);
        expect(order, Topics.INVENTORY_COMMAND, ReserveStock.TYPE, ReserveStock.class);

        reply(order, Topics.INVENTORY_REPLY, StockReserved.TYPE, new StockReserved(order.id()));
        reply(order, Topics.INVENTORY_REPLY, StockReserved.TYPE, new StockReserved(order.id()));

        expect(order, Topics.PAYMENT_COMMAND, Pay.TYPE, Pay.class);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> {
            poll();
            return count(order, Pay.TYPE) == 1;
        });
    }

    @Test
    void invalidReply_goesToDlt_andDoesNotBlockLaterReplies() {
        Order order = place(4L, 0L);
        expect(order, Topics.INVENTORY_COMMAND, ReserveStock.TYPE, ReserveStock.class);

        try (Consumer<String, String> dltConsumer =
                     consumerFactory.createConsumer("test-dlt-" + UUID.randomUUID(), "test")) {
            dltConsumer.subscribe(List.of(Topics.INVENTORY_REPLY + ".DLT"));

            // 본문이 JSON null 인 응답: 재시도하지 않고 DLT 로 옮겨진다
            ProducerRecord<String, String> poison =
                    new ProducerRecord<>(Topics.INVENTORY_REPLY, order.id().toString(), "null");
            poison.headers().add(MessageHeaders.TYPE, StockReserved.TYPE.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(poison).join();

            // 같은 키(같은 파티션)의 다음 정상 응답이 막히지 않는다
            reply(order, Topics.INVENTORY_REPLY, StockReserved.TYPE, new StockReserved(order.id()));
            expect(order, Topics.PAYMENT_COMMAND, Pay.TYPE, Pay.class);

            List<ConsumerRecord<String, String>> dlt = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                dltConsumer.poll(Duration.ofMillis(200)).forEach(dlt::add);
                return dlt.stream().anyMatch(r -> order.id().toString().equals(r.key()));
            });
        }
    }

    private <T> T expect(Order order, String topic, String type, Class<T> payloadType) {
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            poll();
            return find(order, topic, type) != null;
        });
        return json.readValue(find(order, topic, type).value(), payloadType);
    }

    private void poll() {
        consumer.poll(Duration.ofMillis(200)).forEach(received::add);
    }

    private ConsumerRecord<String, String> find(Order order, String topic, String type) {
        return received.stream()
                .filter(r -> order.id().toString().equals(r.key()) && topic.equals(r.topic())
                        && type.equals(header(r, MessageHeaders.TYPE)))
                .findFirst()
                .orElse(null);
    }

    private long count(Order order, String type) {
        return received.stream()
                .filter(r -> order.id().toString().equals(r.key()) && type.equals(header(r, MessageHeaders.TYPE)))
                .count();
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

    private long outboxCount(UUID orderId) {
        return JdbcClient.create(dataSource)
                .sql("select count(*) from outbox where aggregateid = ?")
                .param(orderId.toString())
                .query(Long.class)
                .single();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
