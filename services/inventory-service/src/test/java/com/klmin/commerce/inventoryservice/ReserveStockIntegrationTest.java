package com.klmin.commerce.inventoryservice;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReleaseStock;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.contract.inventory.StockReleased;
import com.klmin.commerce.contract.inventory.StockReservationFailed;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.contract.order.OrderCompleted;
import com.klmin.commerce.inventoryservice.stock.application.port.in.GetStockUseCase;
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
 * inventory.command(ReserveStock) 수신 -> 재고 예약 -> outbox -> relay -> inventory.reply 발행.
 * order-service 대신 테스트가 커맨드를 보낸다. 재고는 V2 시드 데이터(상품 1~5)를 쓴다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ReserveStockIntegrationTest {

    @Autowired
    GetStockUseCase getStockUseCase;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    // 테스트에서 order-service 역할로 커맨드를 보낼 때만 쓴다
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper json;

    private Consumer<String, String> replyConsumer;

    @BeforeEach
    void subscribe() {
        replyConsumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), "test");
        replyConsumer.subscribe(List.of(Topics.INVENTORY_REPLY));
    }

    @AfterEach
    void close() {
        replyConsumer.close();
    }

    @Test
    void reserveStock_repliesStockReserved_andDuplicateDoesNotReserveTwice() {
        int before = getStockUseCase.getStock(1L).available();
        UUID orderId = UUID.randomUUID();
        ReserveStock command = new ReserveStock(orderId, List.of(new ReserveStock.Line(1L, 2)));

        sendCommand(command);
        ConsumerRecord<String, String> reply = awaitReplies(orderId, 1).getFirst();
        assertThat(header(reply, MessageHeaders.TYPE)).isEqualTo(StockReserved.TYPE);
        assertThat(json.readValue(reply.value(), StockReserved.class).orderId()).isEqualTo(orderId);
        assertThat(getStockUseCase.getStock(1L).available()).isEqualTo(before - 2);

        // 같은 커맨드 재전송: 같은 응답을 다시 보내지만 재고는 한 번만 줄어 있다
        sendCommand(command);
        List<ConsumerRecord<String, String>> replies = awaitReplies(orderId, 2);
        assertThat(replies).allMatch(r -> StockReserved.TYPE.equals(header(r, MessageHeaders.TYPE)));
        assertThat(getStockUseCase.getStock(1L).available()).isEqualTo(before - 2);
        assertThat(getStockUseCase.getStock(1L).reserved()).isEqualTo(2);
    }

    @Test
    void insufficientStock_repliesFailed_andReservesNothing() {
        int before = getStockUseCase.getStock(2L).available();
        UUID orderId = UUID.randomUUID();

        // 상품 5는 재고 0
        sendCommand(new ReserveStock(orderId, List.of(new ReserveStock.Line(2L, 1), new ReserveStock.Line(5L, 1))));

        ConsumerRecord<String, String> reply = awaitReplies(orderId, 1).getFirst();
        assertThat(header(reply, MessageHeaders.TYPE)).isEqualTo(StockReservationFailed.TYPE);
        assertThat(json.readValue(reply.value(), StockReservationFailed.class).reason())
                .isEqualTo("insufficient stock: productId=5");
        assertThat(getStockUseCase.getStock(2L).available()).isEqualTo(before);
    }

    @Test
    void invalidCommand_goesToDlt_andDoesNotBlockLaterCommands() {
        UUID orderId = UUID.randomUUID();
        try (Consumer<String, String> dltConsumer =
                     consumerFactory.createConsumer("test-dlt-" + UUID.randomUUID(), "test")) {
            dltConsumer.subscribe(List.of(Topics.INVENTORY_COMMAND + ".DLT"));

            // orderId 없는 커맨드: 재시도하지 않고 DLT 로 옮겨진다
            ProducerRecord<String, String> poison =
                    new ProducerRecord<>(Topics.INVENTORY_COMMAND, orderId.toString(), "{\"lines\":[]}");
            poison.headers().add(MessageHeaders.TYPE, ReserveStock.TYPE.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(poison).join();

            // 같은 키의 다음 정상 커맨드는 처리된다
            sendCommand(new ReserveStock(orderId, List.of(new ReserveStock.Line(3L, 1))));
            assertThat(header(awaitReplies(orderId, 1).getFirst(), MessageHeaders.TYPE))
                    .isEqualTo(StockReserved.TYPE);

            List<ConsumerRecord<String, String>> dlt = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                dltConsumer.poll(Duration.ofMillis(200)).forEach(dlt::add);
                return dlt.stream().anyMatch(r -> orderId.toString().equals(r.key()));
            });
        }
    }

    @Test
    void releaseStock_returnsReservedStock_andRepliesStockReleased() {
        int before = getStockUseCase.getStock(2L).available();
        UUID orderId = UUID.randomUUID();
        sendCommand(new ReserveStock(orderId, List.of(new ReserveStock.Line(2L, 4))));
        awaitReplies(orderId, 1);
        assertThat(getStockUseCase.getStock(2L).available()).isEqualTo(before - 4);

        send(Topics.INVENTORY_COMMAND, orderId, ReleaseStock.TYPE, new ReleaseStock(orderId));
        send(Topics.INVENTORY_COMMAND, orderId, ReleaseStock.TYPE, new ReleaseStock(orderId)); // 중복 보상

        List<ConsumerRecord<String, String>> replies = awaitReplies(orderId, 3);
        assertThat(replies.subList(1, 3)).allMatch(r -> StockReleased.TYPE.equals(header(r, MessageHeaders.TYPE)));
        assertThat(getStockUseCase.getStock(2L).available()).isEqualTo(before);
    }

    @Test
    void orderCompletedEvent_confirmsReservedStock() {
        int reservedBefore = getStockUseCase.getStock(3L).reserved();
        int availableBefore = getStockUseCase.getStock(3L).available();
        UUID orderId = UUID.randomUUID();
        sendCommand(new ReserveStock(orderId, List.of(new ReserveStock.Line(3L, 2))));
        awaitReplies(orderId, 1);

        send(Topics.ORDER_EVENTS, orderId, OrderCompleted.TYPE, new OrderCompleted(orderId, 1L, 2000L, 0L, 2000L));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(getStockUseCase.getStock(3L).reserved()).isEqualTo(reservedBefore));
        assertThat(getStockUseCase.getStock(3L).available()).isEqualTo(availableBefore - 2);
    }

    private void sendCommand(ReserveStock command) {
        send(Topics.INVENTORY_COMMAND, command.orderId(), ReserveStock.TYPE, command);
    }

    private void send(String topic, UUID orderId, String type, Object payload) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, orderId.toString(), json.writeValueAsString(payload));
        record.headers().add(MessageHeaders.ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    private List<ConsumerRecord<String, String>> awaitReplies(UUID orderId, int count) {
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            replyConsumer.poll(Duration.ofMillis(200)).forEach(received::add);
            return received.stream().filter(r -> orderId.toString().equals(r.key())).count() >= count;
        });
        return received.stream().filter(r -> orderId.toString().equals(r.key())).toList();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
