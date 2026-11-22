package com.klmin.commerce.orderservice;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.contract.inventory.StockReservationFailed;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderStatus;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 주문 생성 -> outbox -> 스케줄러 relay -> inventory.command 발행, inventory.reply 수신 -> 주문 상태 반영.
 * inventory-service 대신 테스트가 응답을 직접 보낸다.
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
    ConsumerFactory<String, String> consumerFactory;

    // 테스트에서 inventory-service 역할로 응답을 보낼 때만 쓴다 (서비스 코드는 outbox 만 사용)
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper json;

    @Autowired
    javax.sql.DataSource dataSource;

    private Consumer<String, String> commandConsumer;

    @BeforeEach
    void subscribe() {
        commandConsumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), "test");
        commandConsumer.subscribe(List.of(Topics.INVENTORY_COMMAND));
    }

    @AfterEach
    void close() {
        commandConsumer.close();
    }

    @Test
    void placedOrder_publishesReserveStockThroughOutbox_andConfirmsOnStockReserved() {
        Order order = placeOrderUseCase.placeOrder(new PlaceOrderCommand(1L, List.of(
                new PlaceOrderCommand.Line(10L, 2, 1_000L))));

        ConsumerRecord<String, String> record = awaitCommand(order.id());
        assertThat(header(record, MessageHeaders.TYPE)).isEqualTo(ReserveStock.TYPE);
        assertThat(header(record, MessageHeaders.ID)).isNotBlank();
        ReserveStock command = json.readValue(record.value(), ReserveStock.class);
        assertThat(command.orderId()).isEqualTo(order.id());
        assertThat(command.lines()).containsExactly(new ReserveStock.Line(10L, 2));

        // 발행된 outbox 행은 지워진다
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(outboxCount(order.id())).isZero());

        sendReply(order.id(), StockReserved.TYPE, new StockReserved(order.id()));
        awaitStatus(order, OrderStatus.CONFIRMED);

        // 같은 응답이 다시 오거나 늦은 실패 응답이 와도 상태는 그대로
        sendReply(order.id(), StockReserved.TYPE, new StockReserved(order.id()));
        sendReply(order.id(), StockReservationFailed.TYPE, new StockReservationFailed(order.id(), "late"));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(getOrderUseCase.getOrder(new GetOrderQuery(order.id(), 1L)).status())
                        .isEqualTo(OrderStatus.CONFIRMED));
    }

    @Test
    void stockReservationFailed_rejectsOrder() {
        Order order = placeOrderUseCase.placeOrder(new PlaceOrderCommand(2L, List.of(
                new PlaceOrderCommand.Line(20L, 1, 500L))));
        awaitCommand(order.id());

        sendReply(order.id(), StockReservationFailed.TYPE,
                new StockReservationFailed(order.id(), "insufficient stock: productId=20"));

        awaitStatus(order, OrderStatus.REJECTED);
        assertThat(getOrderUseCase.getOrder(new GetOrderQuery(order.id(), 2L)).rejectReason())
                .isEqualTo("insufficient stock: productId=20");
    }

    @Test
    void invalidReply_goesToDlt_andDoesNotBlockLaterReplies() {
        Order order = placeOrderUseCase.placeOrder(new PlaceOrderCommand(3L, List.of(
                new PlaceOrderCommand.Line(30L, 1, 100L))));
        awaitCommand(order.id());

        try (Consumer<String, String> dltConsumer =
                     consumerFactory.createConsumer("test-dlt-" + UUID.randomUUID(), "test")) {
            dltConsumer.subscribe(List.of(Topics.INVENTORY_REPLY + ".DLT"));

            // 본문이 JSON null 인 응답: 재시도하지 않고 DLT 로 옮겨진다
            ProducerRecord<String, String> poison =
                    new ProducerRecord<>(Topics.INVENTORY_REPLY, order.id().toString(), "null");
            poison.headers().add(MessageHeaders.TYPE, StockReserved.TYPE.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(poison).join();

            // 같은 키(같은 파티션)의 다음 정상 응답이 막히지 않는다
            sendReply(order.id(), StockReserved.TYPE, new StockReserved(order.id()));
            awaitStatus(order, OrderStatus.CONFIRMED);

            List<ConsumerRecord<String, String>> dlt = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                dltConsumer.poll(Duration.ofMillis(200)).forEach(dlt::add);
                return dlt.stream().anyMatch(r -> order.id().toString().equals(r.key()));
            });
            ConsumerRecord<String, String> parked = dlt.stream()
                    .filter(r -> order.id().toString().equals(r.key())).findFirst().orElseThrow();
            assertThat(parked.value()).isEqualTo("null");
            assertThat(header(parked, MessageHeaders.TYPE)).isEqualTo(StockReserved.TYPE);
        }
    }

    private ConsumerRecord<String, String> awaitCommand(UUID orderId) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            commandConsumer.poll(Duration.ofMillis(200)).forEach(r -> {
                if (orderId.toString().equals(r.key())) {
                    found.add(r);
                }
            });
            return !found.isEmpty();
        });
        return found.getFirst();
    }

    private void sendReply(UUID orderId, String type, Object payload) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(Topics.INVENTORY_REPLY, orderId.toString(), json.writeValueAsString(payload));
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
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
