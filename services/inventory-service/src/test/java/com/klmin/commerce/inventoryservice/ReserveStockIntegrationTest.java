package com.klmin.commerce.inventoryservice;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReleaseStock;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.contract.inventory.StockReleased;
import com.klmin.commerce.contract.inventory.StockReservationFailed;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.contract.order.OrderCompleted;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ConfirmStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.GetStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReleaseStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.out.ProcessedMessagePort;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    ReserveStockUseCase reserveStockUseCase;

    @Autowired
    ReleaseStockUseCase releaseStockUseCase;

    @Autowired
    ConfirmStockUseCase confirmStockUseCase;

    @Autowired
    ProcessedMessagePort processedMessagePort;

    @Autowired
    PlatformTransactionManager transactionManager;

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

    // 시나리오 3: relay 재전송·오프셋 되감기로 같은 메시지(같은 id 헤더)가 다시 오면 재고도 응답도 바뀌지 않는다
    @Test
    void redeliveredMessage_isSkipped_withoutSecondReply() {
        int before = getStockUseCase.getStock(1L).available();
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        ReserveStock command = new ReserveStock(orderId, List.of(new ReserveStock.Line(1L, 1)));

        send(Topics.INVENTORY_COMMAND, orderId, ReserveStock.TYPE, command, messageId);
        send(Topics.INVENTORY_COMMAND, orderId, ReserveStock.TYPE, command, messageId);
        // 같은 파티션(key = orderId)이라 이 메시지가 처리됐으면 앞의 재전달도 이미 처리된 것이다
        send(Topics.INVENTORY_COMMAND, orderId, ReleaseStock.TYPE, new ReleaseStock(orderId));

        List<ConsumerRecord<String, String>> replies = awaitReplies(orderId, 2);
        assertThat(replies).extracting(r -> header(r, MessageHeaders.TYPE))
                .containsExactly(StockReserved.TYPE, StockReleased.TYPE);
        assertThat(getStockUseCase.getStock(1L).available()).isEqualTo(before);
    }

    @Test
    void processedMessage_requiresTransaction_andRollsBackWithIt() {
        UUID messageId = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> processedMessagePort.markProcessed(messageId))
                .isInstanceOf(IllegalTransactionStateException.class);

        tx.executeWithoutResult(status -> {
            assertThat(processedMessagePort.markProcessed(messageId)).isTrue();
            status.setRollbackOnly();   // 비즈니스 처리 실패
        });
        // 롤백됐으므로 재전달 때 다시 처리된다. 커밋된 뒤에는 중복으로 걸러진다
        assertThat(tx.<Boolean>execute(status -> processedMessagePort.markProcessed(messageId))).isTrue();
        assertThat(tx.<Boolean>execute(status -> processedMessagePort.markProcessed(messageId))).isFalse();
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

    // 해제(inventory.command)와 확정(order.events)이 같은 주문에 동시에 와도 하나만 반영되고 둘 다 실패하지 않는다
    @RepeatedTest(5)
    void concurrentReleaseAndConfirm_applyExactlyOne() throws Exception {
        UUID orderId = UUID.randomUUID();
        sendCommand(new ReserveStock(orderId, List.of(new ReserveStock.Line(1L, 1))));
        awaitReplies(orderId, 1);
        int availableAfterReserve = getStockUseCase.getStock(1L).available();
        int reservedAfterReserve = getStockUseCase.getStock(1L).reserved();

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
            awaitLatch(start);
            releaseStockUseCase.release(UUID.randomUUID(), orderId);
        });
        CompletableFuture<Void> confirm = CompletableFuture.runAsync(() -> {
            awaitLatch(start);
            confirmStockUseCase.confirm(UUID.randomUUID(), orderId);
        });
        start.countDown();
        CompletableFuture.allOf(release, confirm).get(30, TimeUnit.SECONDS);

        int available = getStockUseCase.getStock(1L).available();
        assertThat(getStockUseCase.getStock(1L).reserved()).isEqualTo(reservedAfterReserve - 1);
        // 해제가 이기면 available 이 1 돌아오고, 확정이 이기면 그대로다
        assertThat(available).isIn(availableAfterReserve, availableAfterReserve + 1);
    }

    // 같은 메시지가 동시에 두 번 처리될 때 (리밸런스 직후, relay 의 짧은 간격 재전송).
    // 먼저 들어온 A 가 커밋하면 늦은 B 는 processed_message PK 에서 기다렸다가 0건이 되어 건너뛴다
    @Test
    void sameMessageConcurrently_firstCommits_laterWaitsThenSkips() throws Exception {
        int availableBefore = getStockUseCase.getStock(2L).available();
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        ReserveStockCommand command = new ReserveStockCommand(orderId, List.of(new ReserveStockCommand.Line(2L, 1)));

        raceSameMessage(() -> reserveStockUseCase.reserve(messageId, command), true);

        assertThat(getStockUseCase.getStock(2L).available()).isEqualTo(availableBefore - 1);
        assertExactlyOneReply(orderId, StockReserved.TYPE);
    }

    // 먼저 들어온 A 가 처리 중 실패해 롤백하면, 기다리던 B 가 기록에 성공해 이어받아 처리한다 (유실 없음)
    @Test
    void sameMessageConcurrently_firstRollsBack_laterTakesOver() throws Exception {
        int availableBefore = getStockUseCase.getStock(2L).available();
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        ReserveStockCommand command = new ReserveStockCommand(orderId, List.of(new ReserveStockCommand.Line(2L, 1)));

        raceSameMessage(() -> reserveStockUseCase.reserve(messageId, command), false);

        assertThat(getStockUseCase.getStock(2L).available()).isEqualTo(availableBefore - 1);
        assertExactlyOneReply(orderId, StockReserved.TYPE);
    }

    /**
     * A 가 같은 메시지를 처리하고 커밋 직전에 멈춘 사이 B 가 같은 메시지를 처리하게 한다.
     * B 가 A 가 끝날 때까지 끝나지 않는 것(= PK 에서 기다림)을 확인한 뒤 A 를 커밋 또는 롤백한다.
     */
    private void raceSameMessage(Runnable process, boolean firstCommits) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstProcessed = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            process.run();   // processed_message INSERT + 재고 변경 + 응답 outbox, 아직 커밋 전
            if (!firstCommits) {
                status.setRollbackOnly();
            }
            firstProcessed.countDown();
            awaitLatch(finishFirst);
        }));
        assertThat(firstProcessed.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> second = CompletableFuture.runAsync(process);
        Thread.sleep(1_000);
        assertThat(second).as("늦은 쪽은 앞 트랜잭션이 끝날 때까지 기다려야 한다").isNotDone();

        finishFirst.countDown();
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
    }

    /** 응답이 한 건 오고, 잠시 더 기다려도 더 오지 않는다 */
    private void assertExactlyOneReply(UUID orderId, String type) {
        assertThat(header(awaitReplies(orderId, 1).getFirst(), MessageHeaders.TYPE)).isEqualTo(type);
        long until = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < until) {
            replyConsumer.poll(Duration.ofMillis(200)).forEach(received::add);
        }
        assertThat(received.stream().filter(r -> orderId.toString().equals(r.key()))).hasSize(1);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void sendCommand(ReserveStock command) {
        send(Topics.INVENTORY_COMMAND, command.orderId(), ReserveStock.TYPE, command);
    }

    private void send(String topic, UUID orderId, String type, Object payload) {
        send(topic, orderId, type, payload, UUID.randomUUID());
    }

    private void send(String topic, UUID orderId, String type, Object payload, UUID messageId) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, orderId.toString(), json.writeValueAsString(payload));
        record.headers().add(MessageHeaders.ID, messageId.toString().getBytes(StandardCharsets.UTF_8));
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
