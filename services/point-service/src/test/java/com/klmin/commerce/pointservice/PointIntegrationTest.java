package com.klmin.commerce.pointservice;

import com.github.f4b6a3.uuid.UuidCreator;
import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.auth.UserRegistered;
import com.klmin.commerce.contract.point.PointRefunded;
import com.klmin.commerce.contract.point.PointUseFailed;
import com.klmin.commerce.contract.point.PointUsed;
import com.klmin.commerce.contract.point.RefundPoint;
import com.klmin.commerce.contract.point.UsePoint;
import com.klmin.commerce.pointservice.point.application.port.in.GetPointBalanceUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.ListMyPointHistoryQuery;
import com.klmin.commerce.pointservice.point.application.port.in.ListMyPointHistoryUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.PointHistoryEntryResult;
import com.klmin.commerce.pointservice.point.application.port.in.UsePointCommand;
import com.klmin.commerce.pointservice.point.application.port.in.UsePointUseCase;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/**
 * point.command(UsePoint, RefundPoint) -> 포인트 차감·환불 -> outbox -> relay -> point.reply.
 * order-service 대신 테스트가 커맨드를 보낸다. 계정은 V2 시드(user1~3: 10,000P, user4: 0P, 고정 UUID).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PointIntegrationTest {

    static final UUID USER_1 = UUID.fromString("01900000-0000-7000-8001-000000000001");
    static final UUID USER_2 = UUID.fromString("01900000-0000-7000-8001-000000000002");
    static final UUID USER_4 = UUID.fromString("01900000-0000-7000-8001-000000000004");

    @Autowired
    GetPointBalanceUseCase getPointBalanceUseCase;

    @Autowired
    UsePointUseCase usePointUseCase;

    @Autowired
    ListMyPointHistoryUseCase listMyPointHistoryUseCase;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    // 테스트에서 order-service 역할로 커맨드를 보낼 때만 쓴다
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

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
        long before = getPointBalanceUseCase.getBalance(USER_1).balance().value();
        UUID orderId = UUID.randomUUID();

        send(orderId, UsePoint.TYPE, new UsePoint(orderId, USER_1, 1_500L));
        assertThat(type(awaitReplies(orderId, 1).getFirst())).isEqualTo(PointUsed.TYPE);
        assertThat(getPointBalanceUseCase.getBalance(USER_1).balance().value()).isEqualTo(before - 1_500L);

        send(orderId, RefundPoint.TYPE, new RefundPoint(orderId));
        send(orderId, RefundPoint.TYPE, new RefundPoint(orderId));
        List<ConsumerRecord<String, String>> replies = awaitReplies(orderId, 3);
        assertThat(replies.subList(1, 3)).allMatch(r -> PointRefunded.TYPE.equals(type(r)));
        assertThat(getPointBalanceUseCase.getBalance(USER_1).balance().value()).isEqualTo(before);
    }

    // 값 객체 도입 전에는 잘못된 커맨드의 거절 기록에 음수 금액이 그대로 남았다. 그 주문의 보상이 막히지 않는다
    @Test
    void legacyRejectedUsage_withInvalidValues_stillRepliesRefunded() {
        UUID orderId = UUID.randomUUID();
        jdbc.update("insert into point_usages (order_id, user_id, amount, status, reason, created_at, updated_at, version) "
                + "values (?, null, -5, 'REJECTED', 'invalid command', now(), now(), 0)", orderId);

        send(orderId, RefundPoint.TYPE, new RefundPoint(orderId));

        assertThat(type(awaitReplies(orderId, 1).getFirst())).isEqualTo(PointRefunded.TYPE);
    }

    @Test
    void insufficientPoints_repliesFailed() {
        UUID orderId = UUID.randomUUID();

        send(orderId, UsePoint.TYPE, new UsePoint(orderId, USER_4, 100L));

        ConsumerRecord<String, String> reply = awaitReplies(orderId, 1).getFirst();
        assertThat(type(reply)).isEqualTo(PointUseFailed.TYPE);
        assertThat(json.readValue(reply.value(), PointUseFailed.class).reason()).isEqualTo("insufficient points: userId=" + USER_4);
        assertThat(getPointBalanceUseCase.getBalance(USER_4).balance().value()).isZero();
    }

    // 같은 UsePoint 메시지가 동시에 두 번 처리될 때: 늦은 쪽은 앞 트랜잭션이 커밋될 때까지
    // processed_message PK 에서 기다렸다가 0건이 되어 건너뛴다. 포인트는 한 번만 차감되고 응답도 한 건
    @Test
    void sameMessageConcurrently_deductsOnce() throws Exception {
        long before = getPointBalanceUseCase.getBalance(USER_2).balance().value();
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UsePointCommand command = new UsePointCommand(orderId, USER_2, 700L);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstProcessed = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            usePointUseCase.use(messageId, command);   // 아직 커밋 전
            firstProcessed.countDown();
            try {
                finishFirst.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
        CompletableFuture<Void> second = null;
        try {
            assertThat(firstProcessed.await(30, TimeUnit.SECONDS)).isTrue();
            second = CompletableFuture.runAsync(() -> usePointUseCase.use(messageId, command));
            Thread.sleep(1_000);
            assertThat(second).as("늦은 쪽은 앞 트랜잭션이 끝날 때까지 기다려야 한다").isNotDone();
        } finally {
            // 검증이 실패해도 앞 트랜잭션(계정 락 보유)을 반드시 끝내 다른 테스트가 막히지 않게 한다
            finishFirst.countDown();
        }
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        assertThat(getPointBalanceUseCase.getBalance(USER_2).balance().value()).isEqualTo(before - 700L);
        assertThat(type(awaitReplies(orderId, 1).getFirst())).isEqualTo(PointUsed.TYPE);
        long until = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < until) {
            replyConsumer.poll(Duration.ofMillis(200)).forEach(received::add);
        }
        assertThat(received.stream().filter(r -> orderId.toString().equals(r.key()))).hasSize(1);
    }

    /** 가입 이벤트가 재전달·중복돼도 계정은 하나, 가입 축하 포인트도 한 번 (후속 2 A-3-2) */
    @Test
    void userRegistered_opensAccountOnce_evenIfRedeliveredOrDuplicated() {
        UUID userId = UuidCreator.getTimeOrderedEpoch();
        UUID otherUserId = UuidCreator.getTimeOrderedEpoch();
        UserRegistered event = new UserRegistered(userId, java.time.Instant.parse("2026-11-23T00:00:00Z"));
        UUID messageId = UUID.randomUUID();
        sendAuthEvent(userId, messageId, event);
        sendAuthEvent(userId, messageId, event);          // 같은 메시지 재전달
        sendAuthEvent(userId, UUID.randomUUID(), event);  // 내용이 같은 다른 메시지

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                .untilAsserted(() -> assertThat(getPointBalanceUseCase.getBalance(userId).balance().value()).isEqualTo(1_000L));
        // 계정이 생긴 뒤 포인트를 쓰고, 늦게 온 같은 이벤트가 잔액을 되돌리지 않는지 본다
        usePointUseCase.use(UUID.randomUUID(), new UsePointCommand(UUID.randomUUID(), userId, 400L));
        sendAuthEvent(userId, UUID.randomUUID(), event);
        UUID marker = UUID.randomUUID();
        sendAuthEvent(otherUserId, marker, new UserRegistered(otherUserId, event.registeredAt()));
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                .untilAsserted(() -> assertThat(getPointBalanceUseCase.getBalance(otherUserId).balance().value()).isEqualTo(1_000L));
        assertThat(getPointBalanceUseCase.getBalance(userId).balance().value()).isEqualTo(600L);
        // 내역: 가입 축하 지급 한 번 + 주문 사용 (F-2a)
        assertThat(listMyPointHistoryUseCase.listMyHistory(new ListMyPointHistoryQuery(userId, null, 20)))
                .extracting(PointHistoryEntryResult::kind, PointHistoryEntryResult::amount)
                .containsExactly(tuple("ORDER", 400L), tuple("SIGNUP_BONUS", 1_000L));
    }

    private void sendAuthEvent(UUID userId, UUID messageId, UserRegistered event) {
        // 키가 userId 라 같은 사용자의 이벤트는 같은 파티션에서 순서대로 처리된다. 확인용 다른 사용자도 같은 파티션에 둔다
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.AUTH_EVENTS, 0, userId.toString(),
                json.writeValueAsString(event));
        record.headers().add(MessageHeaders.ID, messageId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(MessageHeaders.TYPE, UserRegistered.TYPE.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
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
